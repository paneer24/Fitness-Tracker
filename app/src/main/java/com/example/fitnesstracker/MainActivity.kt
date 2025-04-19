package com.example.fitnesstracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.fitnesstracker.data.repository.FitnessRepository
import com.example.fitnesstracker.ui.FitnessViewModel
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var currentLocationMarker: Marker
    private lateinit var startStopButton: Button
    private lateinit var distanceValue: TextView
    private lateinit var caloriesValue: TextView
    private lateinit var pathPolyline: Polyline
    private var firstLocationUpdate = true
    private lateinit var durationValue: TextView

    private val viewModel: FitnessViewModel by viewModels {
        FitnessViewModel.Factory(FitnessRepository(this), context = this)
    }
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startTracking()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        startStopButton = findViewById(R.id.startStopButton)
        distanceValue = findViewById(R.id.distanceValue)
        caloriesValue = findViewById(R.id.caloriesValue)
        durationValue = findViewById(R.id.durationValue)
        setupMap()
        startStopButton.setOnClickListener {
            if (viewModel.isTracking.value == true) {
                stopTracking()
            } else {
                checkPermissionsAndStartTracking()
            }
        }
        setupObservers()
    }
    private fun setupMap() {
        map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(18.0)
        }
        currentLocationMarker = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_location_pin)
            title = "Current Location"
        }
        map.overlays.add(currentLocationMarker)
        pathPolyline = Polyline(map).apply {
            outlinePaint.color = Color.BLUE
            outlinePaint.strokeWidth = 10f
        }
        map.overlays.add(pathPolyline)
        startLocationUpdates()
    }
    private fun startLocationUpdates() {
        if (!checkLocationPermission()) return
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = 1000
            fastestInterval = 500
        }
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val latLng = LatLng(location.latitude, location.longitude)
                    updateLocationMarker(latLng)
                    Log.d("MainActivity", "Location update received: ${location.latitude}, ${location.longitude}")
                }
            }
        }
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Error requesting location updates", e)
        }
    }
    private fun updateLocationMarker(latLng: LatLng) {
        Log.d("MainActivity", "Updating marker to: ${latLng.latitude}, ${latLng.longitude}")
        val geoPoint = GeoPoint(latLng.latitude, latLng.longitude)
        currentLocationMarker.position = geoPoint
        if (firstLocationUpdate) {
            map.controller.setZoom(18.0)
            map.controller.setCenter(geoPoint)
            firstLocationUpdate = false
            Log.d("MainActivity", "First location update - centered map")
        } else if (viewModel.isTracking.value == true) {
            map.controller.animateTo(geoPoint)
        }

        map.invalidate()
    }
    private fun requestCurrentLocation() {
        if (checkLocationPermission()) {
            val locationClient = LocationServices.getFusedLocationProviderClient(this)
            locationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    val latLng = LatLng(location.latitude, location.longitude)
                    updateLocationMarker(latLng)
                    Log.d("MainActivity", "Current location: ${location.latitude}, ${location.longitude}")
                }
            }
        }
    }
    private fun setupObservers() {
        viewModel.isTracking.observe(this) { isTracking ->
            Log.d("MainActivity", "Tracking status changed: $isTracking")
            startStopButton.text = if (isTracking) "Stop" else "Start"
        }
        viewModel.duration.observe(this) { duration ->
            durationValue.text = viewModel.formatDuration(duration)
            Log.d("MainActivity", "Duration updated: ${viewModel.formatDuration(duration)}")
        }
        viewModel.routePoints.observe(this) { points ->
            Log.d("MainActivity", "Received ${points.size} route points")
            if (points.isNotEmpty()) {
                updateRouteOnMap(points)
            }
        }
        viewModel.currentLocation.observe(this) { location ->
            updateLocationMarker(location)
            Log.d("MainActivity", "Marker updated to: ${location.latitude}, ${location.longitude}")
        }
        viewModel.calories.observe(this) { calories ->
            caloriesValue.text = viewModel.formatCalories(calories)
            Log.d("MainActivity", "Calories updated: $calories")
        }

        viewModel.distance.observe(this) { distance ->
            Log.d("MainActivity", "Distance updated: $distance")
            distanceValue.text = viewModel.formatDistance(distance)
        }
    }
    private fun enableMyLocation() {
        if (checkLocationPermission()) {
            getCurrentLocation()
        }
    }
    private fun getCurrentLocation() {
        if (checkLocationPermission()) {
            val locationClient = LocationServices.getFusedLocationProviderClient(this)
            locationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    val latLng = LatLng(location.latitude, location.longitude)
                    Log.d("MainActivity", "Initial location: ${location.latitude}, ${location.longitude}")
                    updateLocationMarker(latLng)
                } ?: run {
                    // If last location is null, request a fresh location
                    requestFreshLocation()
                }
            }
        }
    }
    private fun requestFreshLocation() {
        if (checkLocationPermission()) {
            val locationRequest = LocationRequest.create().apply {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                interval = 1000
                numUpdates = 1  // Just get one update
            }
            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        val latLng = LatLng(location.latitude, location.longitude)
                        Log.d("MainActivity", "Fresh location: ${location.latitude}, ${location.longitude}")
                        updateLocationMarker(latLng)
                    }
                    LocationServices.getFusedLocationProviderClient(this@MainActivity)
                        .removeLocationUpdates(this)
                }
            }
            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        }
    }
    private fun checkLocationPermission(): Boolean {
        return (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED)
    }
    private fun checkPermissionsAndStartTracking() {
        if (checkLocationPermission()) {
            startTracking()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
    private fun startTracking() {
        viewModel.startWorkout()
    }

    private fun stopTracking() {
        viewModel.stopWorkout()
    }
    private fun updateRouteOnMap(points: List<LatLng>) {
        if (points.isEmpty()) return

        try {
            val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
            val currentLocation = geoPoints.last()
            updateLocationMarker(LatLng(currentLocation.latitude, currentLocation.longitude))
            pathPolyline.setPoints(geoPoints)
            if (viewModel.isTracking.value == true) {
                map.controller.animateTo(currentLocation)
            }
            map.invalidate()
            Log.d("MainActivity", "Updated location: ${currentLocation.latitude}, ${currentLocation.longitude}")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error updating map", e)
        }
    }
    override fun onResume() {
        super.onResume()
        map.onResume()
    }
    override fun onPause() {
        super.onPause()
        map.onPause()
    }
    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopWorkout()
    }
}