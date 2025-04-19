package com.example.fitnesstracker.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.*
import com.google.android.gms.maps.model.LatLng
import com.example.fitnesstracker.data.models.WorkoutSession
import com.example.fitnesstracker.data.models.User
import com.example.fitnesstracker.data.repository.FitnessRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.jvm.java
import kotlin.text.format
class FitnessViewModel(
    private val repository: FitnessRepository,
    private val context: Context
) : ViewModel() {
    val currentLocation: LiveData<LatLng> = repository.currentLocation
    // Existing LiveData declarations remain the same
    private val _distance = MutableLiveData<Float>(0f)
    val distance: LiveData<Float> = _distance

    private val _calories = MutableLiveData<Float>(0f)
    val calories: LiveData<Float> = _calories

    private val _isTracking = MutableLiveData<Boolean>(false)
    val isTracking: LiveData<Boolean> = _isTracking

    private val _duration = MutableLiveData<Long>(0L)
    val duration: LiveData<Long> = _duration

    private val _pace = MutableLiveData<Double>(0.0)
    val pace: LiveData<Double> = _pace

    val routePoints: LiveData<List<LatLng>> = repository.routePoints

    private val _currentUser = MutableLiveData<User>()
    val currentUser: LiveData<User> = _currentUser

    private var durationUpdateJob: Job? = null
    private var activeTime: Long = 0
    private var lastUpdateTime: Long = 0

    init {
        repository.totalDistance.observeForever { newDistance ->
            _distance.value = newDistance
            updateCalories()
            updatePace()
        }
        repository.isTracking.observeForever { isTracking ->
            _isTracking.value = isTracking
            if (!isTracking) {
                lastUpdateTime = 0
            }
        }
        loadUserData()
    }
    private fun loadUserData() { val currentLocation: LiveData<LatLng> = repository.currentLocation
        viewModelScope.launch {

            val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val user = User(
                id = sharedPreferences.getString("user_id", UUID.randomUUID().toString()) ?: "",
                weight = sharedPreferences.getFloat("user_weight", 70f),  // default 70kg
                height = sharedPreferences.getFloat("user_height", 170f), // default 170cm
                age = sharedPreferences.getInt("user_age", 25)           // default 25 years
            )

            _currentUser.value = user
        }
    }
    private fun startDurationUpdates() {
        durationUpdateJob?.cancel()
        durationUpdateJob = viewModelScope.launch {
            while (isActive && _isTracking.value == true) {
                _duration.value = repository.getCurrentDuration()
                updateCalories()
                delay(1000)
            }
        }
    }
    fun startWorkout() {
        viewModelScope.launch {
            _duration.value = 0
            _calories.value = 0f
            _distance.value = 0f
            _pace.value = 0.0
            activeTime = 0
            lastUpdateTime = System.currentTimeMillis()
            _isTracking.value = true
            repository.startTracking()
            startDurationUpdates()
        }
    }
    private fun updateCalories() {
        val weight = _currentUser.value?.weight ?: 70f
        val distance = _distance.value ?: 0f
        val duration = _duration.value ?: 0L

        Log.d("FitnessViewModel", "Updating calories - Weight: $weight, Distance: $distance, Duration: $duration")

        val newCalories = repository.calculateCalories(
            weight = weight,
            distance = distance,
            duration = duration
        )
        Log.d("FitnessViewModel", "New calories value: $newCalories")
        _calories.value = newCalories
    }
    private fun updatePace() {
        val distance = _distance.value ?: 0f
        val duration = _duration.value ?: 0L
        if (duration > 0) {
            _pace.value = repository.calculatePace(distance, duration)
        }
    }
    fun stopWorkout() {
        _isTracking.value = false
        repository.stopTracking()
        stopDurationUpdates()
        lastUpdateTime = 0
        activeTime = 0
        saveWorkoutSession()
    }
    fun pauseWorkout() {
        _isTracking.value = false
        repository.stopTracking()
        stopDurationUpdates()
        lastUpdateTime = 0
    }
    fun resumeWorkout() {
        viewModelScope.launch {
            lastUpdateTime = System.currentTimeMillis()
            _isTracking.value = true
            repository.startTracking()
            startDurationUpdates()
        }
    }
    fun clearWorkout() {
        _distance.value = 0f
        _calories.value = 0f
        _duration.value = 0L
        _pace.value = 0.0
        activeTime = 0
        lastUpdateTime = 0
        repository.clearTracking()
    }
    fun formatDuration(duration: Long): String {
        val seconds = (duration / 1000) % 60
        val minutes = (duration / (1000 * 60)) % 60
        val hours = duration / (1000 * 60 * 60)
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
    fun formatPace(pace: Double): String {
        return String.format("%.2f km/h", pace)
    }
    fun formatDistance(distance: Float): String {
        return String.format("%.2f km", distance)
    }
    fun formatCalories(calories: Float): String {
        return String.format("%.0f kcal", calories)
    }
    private fun saveWorkoutSession() {
        viewModelScope.launch {
            val session = WorkoutSession(
                id = UUID.randomUUID().toString(),
                distance = _distance.value ?: 0f,
                duration = _duration.value ?: 0L,
                caloriesBurned = _calories.value ?: 0f,
                routePoints = routePoints.value ?: emptyList(),
                averagePace = _pace.value ?: 0.0,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopDurationUpdates()
        repository.stopTracking()
    }
    private fun stopDurationUpdates() {
        durationUpdateJob?.cancel()
        durationUpdateJob = null
        _duration.value = repository.getCurrentDuration()
    }


    class Factory(
        private val repository: FitnessRepository,
        private val context: Context
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(FitnessViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return FitnessViewModel(repository, context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}