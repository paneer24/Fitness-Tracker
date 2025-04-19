package com.example.fitnesstracker.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import kotlin.apply
import kotlin.collections.last
import kotlin.collections.toMutableList
import kotlin.let
import kotlin.run
class FitnessRepository(private val context: Context) {
    private val locationClient = LocationServices.getFusedLocationProviderClient(context)
    private var lastValidLocation: Location? = null
    private var isActuallyMoving = false
    private var totalDuration: Long = 0
    private var trackingStartTime: Long = 0
    private val MOVEMENT_THRESHOLD = 1.0f // 1 meter threshold
    private val _routePoints = MutableLiveData<List<LatLng>>(emptyList())
    val routePoints: LiveData<List<LatLng>> = _routePoints
    private val _totalDistance = MutableLiveData<Float>(0f)
    val totalDistance: LiveData<Float> = _totalDistance
    private val _isTracking = MutableLiveData<Boolean>(false)
    val isTracking: LiveData<Boolean> = _isTracking
    private val _currentLocation = MutableLiveData<LatLng>()
    val currentLocation: LiveData<LatLng> = _currentLocation
    private var startTime: Long = 0
    private var activeMovementTime: Long = 0
    private var lastMovementTime: Long = 0
    private var lastLocationUpdateTime: Long = 0
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { newLocation ->
                Log.d("FitnessRepository", "New location received: ${newLocation.latitude}, ${newLocation.longitude}")
                val currentTime = System.currentTimeMillis()
                _currentLocation.postValue(LatLng(newLocation.latitude, newLocation.longitude))
                if (newLocation.accuracy > 20f) {
                    Log.d("FitnessRepository", "Location accuracy too poor: ${newLocation.accuracy}")
                    isActuallyMoving = false  // Explicitly set to false
                    return
                }
                lastValidLocation?.let { lastLocation ->
                    val distance = newLocation.distanceTo(lastLocation)
                    val timeGap = currentTime - lastLocationUpdateTime
                    val speed = if (timeGap > 0) (distance * 1000 / timeGap) else 0f
                    isActuallyMoving = distance > MOVEMENT_THRESHOLD &&
                            speed < 8f &&     // Max speed ~29 km/h
                            speed > 0.3f      // Min speed ~1 km/h
                    if (isActuallyMoving) {
                        updateMovementTime(currentTime)
                        updateLocationData(newLocation)
                        Log.d("FitnessRepository", "Valid movement: $distance meters, Speed: $speed m/s")
                    } else {
                        Log.d("FitnessRepository", "No valid movement: Distance=$distance m, Speed=$speed m/s")
                    }
                } ?: run {
                    lastValidLocation = newLocation
                    isActuallyMoving = false
                }
                lastLocationUpdateTime = currentTime
            }
        }
    }
    private fun updateMovementTime(currentTime: Long) {
        if (lastMovementTime > 0) {
            activeMovementTime += currentTime - lastMovementTime
        }
        lastMovementTime = currentTime
    }
    fun getCurrentDuration(): Long {
        return if (_isTracking.value == true) {
            System.currentTimeMillis() - trackingStartTime
        } else {
            totalDuration
        }
    }
    private fun updateLocationData(newLocation: Location) {
        if (!isActuallyMoving) {
            Log.d("FitnessRepository", "Skipping location update - not moving")
            return
        }
        val latLng = LatLng(newLocation.latitude, newLocation.longitude)
        val currentPoints = _routePoints.value?.toMutableList() ?: mutableListOf()
        if (currentPoints.isEmpty()) {
            currentPoints.add(latLng)
            _routePoints.postValue(currentPoints)
            Log.d("FitnessRepository", "Added first route point")
            return
        }
        val distanceFromLast = calculateDistance(currentPoints.last(), latLng)
        if (distanceFromLast > MOVEMENT_THRESHOLD/1000f) {  // Convert threshold to km
            currentPoints.add(latLng)
            _routePoints.postValue(currentPoints)
            val currentTotal = _totalDistance.value ?: 0f
            val newTotal = currentTotal + distanceFromLast
            _totalDistance.postValue(newTotal)
            Log.d("FitnessRepository", "Added point to route. Distance: $distanceFromLast km, Total: $newTotal km")
        }
        lastValidLocation = newLocation
    }
    private fun initializeTracking() {
        startTime = System.currentTimeMillis()
        trackingStartTime = System.currentTimeMillis()
        _isTracking.postValue(true)
    }
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = 1000      // Update every second
            fastestInterval = 500 // Fastest possible updates
            smallestDisplacement = 1f // Minimum 1 meter movement
        }
        if (checkLocationPermission()) {
            try {
                locationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    context.mainLooper
                )
                Log.d("FitnessRepository", "Location updates requested")
            } catch (e: Exception) {
                Log.e("FitnessRepository", "Error requesting location updates", e)
            }
        }
    }
     fun startTracking() {
        if (checkLocationPermission()) {
            initializeTracking()
            requestLocationUpdates()
        }
    }
    fun stopTracking() {
        _isTracking.postValue(false)
        locationClient.removeLocationUpdates(locationCallback)
        totalDuration = System.currentTimeMillis() - trackingStartTime
        resetTimers()
    }
    private fun resetTimers() {
        lastMovementTime = 0
        activeMovementTime = 0
        trackingStartTime = 0
        totalDuration = 0
    }
    fun clearTracking() {
        _routePoints.postValue(emptyList())
        _totalDistance.postValue(0f)
        _isTracking.postValue(false)
        startTime = 0
        resetTimers()
        lastValidLocation = null
        isActuallyMoving = false
    }
    private fun checkLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun calculateDistance(point1: LatLng, point2: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            point1.latitude, point1.longitude,
            point2.latitude, point2.longitude,
            results
        )
        return results[0] / 1000
    }


    fun calculateCalories(weight: Float, distance: Float, duration: Long): Float {
        if (duration < 1000) {
            return 0f
        }
        val hours = duration / (1000.0 * 60.0 * 60.0)
        if (hours <= 0) return 0f
        val speed = if (hours > 0) distance / hours else 0.0
        val met = when {
            speed <= 4.0 -> 2.0
            speed <= 8.0 -> 7.0
            speed <= 11.0 -> 8.5
            else -> 10.0
        }
        val calories = (met * weight * hours).toFloat()
        Log.d("FitnessRepository", "Calories calculated: $calories (MET: $met, Speed: $speed km/h)")
        return calories
    }
    fun calculatePace(distance: Float, duration: Long): Double {
        if (distance <= 0 || duration <= 0) return 0.0
        val hours = duration / (1000.0 * 60.0 * 60.0)
        return distance / hours
    }
}