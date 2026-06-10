package com.example.trackerapp.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
@JvmSuppressWildcards
interface TrackDao {
    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Insert
    suspend fun insertTrackPoint(point: TrackPointEntity): Long

    @androidx.room.Update
    suspend fun updateTrackPoints(points: List<TrackPointEntity>)

    @Query("SELECT * FROM tracks ORDER BY startTime DESC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY startTime DESC LIMIT 1")
    fun getLatestTrack(): Flow<TrackEntity?>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    fun getPointsForTrack(trackId: Long): Flow<List<TrackPointEntity>>
    
    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    suspend fun getPointsForTrackSync(trackId: Long): List<TrackPointEntity>
}
