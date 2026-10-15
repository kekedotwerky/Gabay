package com.example.gabay.services

import android.util.Log
import com.example.gabay.data.UserProgress
import com.example.gabay.data.UserProfile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.auth.auth

object SupabaseService {
        private const val SUPABASE_URL = "https://agqhlhqyrsztzowmkdyy.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZ6cGpzbWJwZ3FsYW5xemVpcXNiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTk2NzI3MjUsImV4cCI6MjA3NTI0ODcyNX0.Iwz5iXkQxbACmYgJ6EB7bXuX76tLPYPJSZPF33N9k-s"

    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = SUPABASE_URL,
        supabaseKey = SUPABASE_KEY
    ) {
        install(io.github.jan.supabase.postgrest.Postgrest)
        install(io.github.jan.supabase.realtime.Realtime)
        install(io.github.jan.supabase.auth.Auth)
    }

    // Get current user ID from auth
    fun getCurrentUserId(): String? {
        return try {
            client.auth.currentSessionOrNull()?.user?.id
        } catch (e: Exception) {
            Log.e("Supabase", "Error getting current user: ${e.message}")
            null
        }
    }

    suspend fun updateUserProgress(chapter: Int, level: Int): Boolean {
        return try {
            val userId = getCurrentUserId() ?: return false
            val progress = UserProgress(userId, chapter, level)
            client.from("user_progress").upsert(progress)
            Log.d("Supabase", "Progress updated: Chapter $chapter, Level $level")
            true
        } catch (e: Exception) {
            Log.e("Supabase", "Error updating progress: ${e.message}")
            false
        }
    }

    suspend fun getCompletedLevelsCount(chapter: Int): Int {
        return try {
            val userId = getCurrentUserId() ?: return 0

            // SIMPLE APPROACH: Get all completed levels and count them locally
            val completedLevels = client.from("user_progress")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("chapter_number", chapter)
                        eq("completed", true)
                    }
                }
                .decodeList<UserProgress>()

            completedLevels.size
        } catch (e: Exception) {
            Log.e("Supabase", "Error counting completed levels: ${e.message}")
            0
        }
    }

    suspend fun getUserProgress(chapter: Int): List<UserProgress> {
        return try {
            val userId = getCurrentUserId() ?: return emptyList()

            client.from("user_progress")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("chapter_number", chapter)
                    }
                }
                .decodeList()
        } catch (e: Exception) {
            Log.e("Supabase", "Error fetching progress: ${e.message}")
            emptyList()
        }
    }

    suspend fun getUserProfile(): UserProfile? {
        return try {
            val userId = getCurrentUserId() ?: return null

            client.from("user_profiles")
                .select {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeSingleOrNull()
        } catch (e: Exception) {
            Log.e("Supabase", "Error fetching user profile: ${e.message}")
            null
        }
    }

    suspend fun updateUserProfile(profile: UserProfile): Boolean {
        return try {


            client.from("user_profiles").upsert(profile)
            true
        } catch (e: Exception) {
            Log.e("Supabase", "Error updating profile: ${e.message}")
            false
        }
    }

    // Get total progress percentage across all chapters
    suspend fun getTotalProgressPercentage(): Int {
        return try {
            val userId = getCurrentUserId() ?: return 0

            // SIMPLE APPROACH: Get all completed levels and count locally
            val completedLevels = client.from("user_progress")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("completed", true)
                    }
                }
                .decodeList<UserProgress>()

            val totalCompleted = completedLevels.size

            // Calculate percentage (assuming 5 chapters with ~30 levels each)
            val totalPossibleLevels = 5 * 30 // Adjust based on your actual level counts

            if (totalPossibleLevels > 0) {
                (totalCompleted * 100) / totalPossibleLevels
            } else {
                0
            }
        } catch (e: Exception) {
            Log.e("Supabase", "Error calculating total progress: ${e.message}")
            0
        }
    }
}