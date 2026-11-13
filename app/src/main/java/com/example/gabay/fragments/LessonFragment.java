package com.example.gabay.fragments;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.content.Intent;
import android.widget.Toast;
import android.widget.VideoView;

import com.example.gabay.R;
import com.example.gabay.activities.QuizActivity;
import com.example.gabay.data.LessonData;
import com.example.gabay.data.LessonDescriptionData;
import com.example.gabay.services.SupabaseJavaService;
import com.example.gabay.viewmodels.ProgressViewModel;

public class LessonFragment extends Fragment {

    private static final String ARG_CHAPTER_ID = "chapterId";
    private static final String ARG_LEVEL_NUMBER = "levelNumber";

    private String chapterId;
    private int levelNumber;
    private VideoView videoView;
    private Button btnNext, btnPrev;
    private TextView levelDescription, levelCounter;
    private android.widget.ProgressBar video_progressBar;
    private ImageButton btnReplay, btnPausePlay;

    private ProgressViewModel progressViewModel;
    private Handler progressHandler = new Handler();

    public LessonFragment() {}

    public static LessonFragment newInstance(String chapterId, int levelNumber) {
        LessonFragment fragment = new LessonFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CHAPTER_ID, chapterId);
        args.putInt(ARG_LEVEL_NUMBER, levelNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            chapterId = getArguments().getString(ARG_CHAPTER_ID);
            levelNumber = getArguments().getInt(ARG_LEVEL_NUMBER, 1);
        }

        progressViewModel = new ViewModelProvider(requireActivity()).get(ProgressViewModel.class);
        Log.d("ProgressDebug", "LessonFragment: Chapter " + chapterId + ", Level " + levelNumber);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_lesson, container, false);

        videoView = view.findViewById(R.id.videoView);
        btnNext = view.findViewById(R.id.btn_nextLevel);
        btnPrev = view.findViewById(R.id.btn_prevLevel);
        btnReplay = view.findViewById(R.id.btn_replay);
        btnPausePlay = view.findViewById(R.id.btn_pause_play);
        video_progressBar = view.findViewById(R.id.progressBar);
        levelDescription = view.findViewById(R.id.levelDescription);
        levelCounter = view.findViewById(R.id.levelCounter);


        showLesson();

        btnNext.setOnClickListener(v -> completeAndGoToNext());
        if (progressViewModel != null) {
            progressViewModel.refreshAllData();
        }

        btnPrev.setOnClickListener(v -> {
            if (levelNumber > 1) goToLevel(levelNumber - 1);
        });

        return view;
    }

    @SuppressLint("SetTextI18n")
    private void showLesson() {
        // These now return arrays with resource IDs (int) instead of Strings
        LessonData.Lesson[] lessons = getLessonsForChapter();
        LessonDescriptionData.LessonDescription[] descriptions = getDescriptionsForChapter();

        if (lessons == null || descriptions == null || levelNumber < 1 || levelNumber > lessons.length || levelNumber > descriptions.length) {
            Log.e("LessonDebug", "Invalid lesson or description data for: Chapter " + chapterId + ", Level " + levelNumber);
            return; // Safe exit
        }

        // These objects now contain resource IDs
        LessonData.Lesson lesson = lessons[levelNumber - 1];
        LessonDescriptionData.LessonDescription description = descriptions[levelNumber - 1];

        // Set title using the resource ID
        if (getActivity() != null) {
            TextView actionBarTitle = getActivity().findViewById(R.id.action_bar_title);
            // ✅ CORRECT: Use setText with the integer resource ID
            if (actionBarTitle != null) actionBarTitle.setText(lesson.titleResId);
        }

        int totalLevels = lessons.length;
        // ✅ CORRECT: Use the formatted string resource for translation
        levelCounter.setText(getString(R.string.level_counter_format, levelNumber, totalLevels));

        // Set description using the resource ID
        if (levelDescription != null) {
            levelDescription.setText(description.descriptionResId);

        }

        // --- Video setup remains the same ---
        String path = "android.resource://" + requireContext().getPackageName() + "/" + lesson.videoResId; // Assuming field is videoResId
        Uri uri = Uri.parse(path);
        videoView.setVideoURI(uri);

        btnReplay.setVisibility(View.GONE);
        btnPausePlay.setVisibility(View.GONE);
        video_progressBar.setProgress(0);
        btnNext.setEnabled(false); // lock initially

        int chapterNumber = extractChapterNumber(chapterId);

        videoView.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            videoView.start();

            // ✅ CORRECT: Get the translated string for logging purposes
            Log.d("LessonDebug", "Video prepared successfully: " + getString(lesson.titleResId));

            new Thread(() -> {
                int completedCount = SupabaseJavaService.getCompletedLevelsCount(chapterNumber);
                boolean alreadyCompleted = completedCount >= levelNumber;

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        setupVideoPlayback(lesson, alreadyCompleted, chapterNumber);
                        btnNext.setEnabled(alreadyCompleted);

                        if (alreadyCompleted) {
                            Log.d("LessonDebug", "Level " + levelNumber + " already completed — Next enabled.");
                        }
                    });
                }
            }).start();
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(getContext(), "Error playing video", Toast.LENGTH_SHORT).show();
            Log.e("LessonDebug", "Video playback error: what=" + what + ", extra=" + extra);
            return true;
        });
    }



    @SuppressLint("ClickableViewAccessibility")
    private void setupVideoPlayback(LessonData.Lesson lesson, boolean alreadyCompleted, int chapterNumber) {
        Runnable progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (videoView != null && videoView.isPlaying()) {
                    int position = videoView.getCurrentPosition();
                    int duration = videoView.getDuration();
                    if (duration > 0) {
                        int progress = (int) (((float) position / duration) * 100);
                        video_progressBar.setProgress(progress);

                        if (progress >= 90 && !btnNext.isEnabled() && !alreadyCompleted) {
                            progressViewModel.refreshAllData();
                            btnNext.setEnabled(true);
                        }
                    }
                }
                progressHandler.postDelayed(this, 10);
            }
        };

        // ✅ Ensure handler starts after video begins
        progressHandler.postDelayed(progressRunnable, 10);

        btnPausePlay.setVisibility(View.VISIBLE);
        btnPausePlay.setImageResource(R.drawable.ic_pause);

        // Auto-hide play/pause button
        btnPausePlay.animate()
                .alpha(0f)
                .setDuration(300)
                .setStartDelay(2500)
                .withEndAction(() -> btnPausePlay.setVisibility(View.GONE))
                .start();

        videoView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                btnPausePlay.animate().cancel();
                btnPausePlay.setAlpha(1f);
                btnPausePlay.setVisibility(View.VISIBLE);

                if (videoView.isPlaying()) {
                    videoView.pause();
                    btnPausePlay.setImageResource(R.drawable.ic_play);
                } else {
                    videoView.start();
                    btnPausePlay.setImageResource(R.drawable.ic_pause);
                }

                btnPausePlay.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .setStartDelay(500)
                        .withEndAction(() -> btnPausePlay.setVisibility(View.GONE))
                        .start();
            }
            return true;
        });

        videoView.setOnCompletionListener(mp -> {
            btnPausePlay.setVisibility(View.GONE);
            btnReplay.setVisibility(View.VISIBLE);
            video_progressBar.setProgress(100);
            progressHandler.removeCallbacks(progressRunnable);
            btnNext.setEnabled(true);

            btnReplay.setAlpha(0f);
            btnReplay.animate().alpha(1f).setDuration(300).start();
        });

        btnReplay.setOnClickListener(v -> {
            btnReplay.setVisibility(View.GONE);
            video_progressBar.setProgress(0);
            videoView.seekTo(0);
            videoView.start();
            btnPausePlay.setImageResource(R.drawable.ic_pause);
            btnPausePlay.setVisibility(View.VISIBLE);
            progressHandler.post(progressRunnable);
        });
    }

    private void completeAndGoToNext() {
        int chapterNumber = extractChapterNumber(chapterId);
        LessonData.Lesson[] lessons = getLessonsForChapter();
        boolean isLastLevel = lessons != null && levelNumber >= lessons.length;
        
        // Update progress first
        markLevelsUpToCurrent(isLastLevel, chapterNumber);

        // Wait for progress update to complete before navigating
        new Handler().postDelayed(() -> {
            if (lessons != null && (levelNumber + 1) <= lessons.length) {
                // Go to next level - progress already updated
                goToLevel(levelNumber + 1);
            } else if (isLastLevel) {
                // Last level completed - check if chapter is fully completed and redirect to quiz
                checkAndRedirectToQuiz(chapterNumber);
            } else if (getActivity() != null) {
                // Ensure progress is updated before finishing
                Intent resultIntent = new Intent();
                resultIntent.putExtra("completed_level", levelNumber);
                resultIntent.putExtra("level_completed", true);
                resultIntent.putExtra("score", 100);
                getActivity().setResult(android.app.Activity.RESULT_OK, resultIntent);
                getActivity().finish();
            }
        }, 500); // Reduced delay - progress update happens immediately in ViewModel
    }
    
    private void checkAndRedirectToQuiz(int chapterNumber) {
        if (getActivity() == null) return;
        
        // Check if chapter is completed (all levels done)
        new Thread(() -> {
            try {
                // Wait a bit for the progress update to complete
                Thread.sleep(200);
                
                int completedCount = SupabaseJavaService.getCompletedLevelsCount(chapterNumber);
                int maxLevels = progressViewModel.getMaxLevelsForChapter(chapterNumber);
                
                // Check if we just completed the last level
                boolean isChapterCompleted = (levelNumber >= maxLevels) && (completedCount >= maxLevels);
                
                Log.d("LessonDebug", "Chapter " + chapterNumber + " - Level " + levelNumber + 
                      " completed. Completed count: " + completedCount + "/" + maxLevels + 
                      ". Is chapter completed: " + isChapterCompleted);
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (isChapterCompleted) {
                            // Chapter is fully completed - redirect to quiz immediately
                            // Note: Congratulatory message will be shown in HomePage when user returns
                            Log.d("LessonDebug", "Chapter " + chapterNumber + " completed! Redirecting to quiz...");
                            openQuizActivity(chapterNumber);
                        } else {
                            // Not fully completed yet, just finish the activity with result
                            if (getActivity() != null) {
                                Intent resultIntent = new Intent();
                                resultIntent.putExtra("completed_level", levelNumber);
                                resultIntent.putExtra("level_completed", true);
                                resultIntent.putExtra("score", 100);
                                getActivity().setResult(android.app.Activity.RESULT_OK, resultIntent);
                                getActivity().finish();
                            }
                        }
                    });
                }
            } catch (Exception e) {
                Log.e("LessonDebug", "Error checking chapter completion: " + e.getMessage());
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (getActivity() != null) {
                            Intent resultIntent = new Intent();
                            resultIntent.putExtra("completed_level", levelNumber);
                            resultIntent.putExtra("level_completed", true);
                            resultIntent.putExtra("score", 100);
                            getActivity().setResult(android.app.Activity.RESULT_OK, resultIntent);
                            getActivity().finish();
                        }
                    });
                }
            }
        }).start();
    }
    
    // Note: This method is preserved for potential use in other activities (e.g., HomePage)
    // when detecting 100% completion to avoid overlap with quiz instruction dialogs
    private void checkAndShowCongratulatoryMessage(int chapterNumber) {
        if (progressViewModel == null || getActivity() == null) {
            Log.e("LessonDebug", "Cannot check congratulatory message - ViewModel or Activity is null");
            return;
        }

        Log.d("LessonDebug", "🏆 Checking if user has reached 100% completion for congratulatory message...");
        
        // Check current total progress
        Integer currentProgress = progressViewModel.getTotalProgress().getValue();
        Log.d("LessonDebug", "Current total progress: " + currentProgress + "%");
        
        if (currentProgress != null && currentProgress >= 100) {
            Log.d("LessonDebug", "🎉 USER HAS REACHED 100% COMPLETION! Showing congratulatory message...");
            showCongratulatoryMessage();
        } else {
            Log.d("LessonDebug", "Progress is " + currentProgress + "% - not yet 100%, no congratulatory message");
        }
    }
    
    private void showCongratulatoryMessage() {
        if (getActivity() == null) {
            Log.e("LessonDebug", "Cannot show congratulatory message - Activity is null");
            return;
        }

        try {
            Log.d("LessonDebug", "🎉 Creating congratulatory message dialog");
            
            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(requireActivity(), R.style.BlackTextDialog);
            builder.setTitle(R.string.final_completion_title);
            builder.setMessage(R.string.final_completion_message);
            builder.setPositiveButton(R.string.final_completion_button, (dialog, which) -> {
                Log.d("LessonDebug", "✅ Congratulatory message dismissed by user");
                // User can continue - dialog will dismiss and quiz will open after delay
            });
            builder.setCancelable(false);
            
            androidx.appcompat.app.AlertDialog dialog = builder.create();
            dialog.show();

            // Style the positive button
            Button positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            if (positiveButton != null) {
                positiveButton.setTextColor(getResources().getColor(R.color.secondaryColor));
                Log.d("LessonDebug", "✅ Congratulatory message button styled successfully");
            }

            Log.d("LessonDebug", "🏆 Congratulatory message displayed successfully!");
            
        } catch (Exception e) {
            Log.e("LessonDebug", "❌ Error showing congratulatory message: " + e.getMessage(), e);
        }
    }

    private void openQuizActivity(int chapterNumber) {
        try {
            Intent intent = new Intent(getActivity(), QuizActivity.class);
            intent.putExtra("chapter_number", chapterNumber);
            startActivity(intent);
            
            // Finish the lesson activity after starting quiz with result
            if (getActivity() != null) {
                Intent resultIntent = new Intent();
                resultIntent.putExtra("completed_level", levelNumber);
                resultIntent.putExtra("level_completed", true);
                resultIntent.putExtra("score", 100);
                getActivity().setResult(android.app.Activity.RESULT_OK, resultIntent);
                getActivity().finish();
            }
            
            Log.d("LessonDebug", "QuizActivity started for chapter " + chapterNumber);
        } catch (Exception e) {
            Log.e("LessonDebug", "Error starting QuizActivity: " + e.getMessage(), e);
            if (getActivity() != null) {
                Toast.makeText(getActivity(), "Error opening quiz", Toast.LENGTH_SHORT).show();
                Intent resultIntent = new Intent();
                resultIntent.putExtra("completed_level", levelNumber);
                resultIntent.putExtra("level_completed", true);
                resultIntent.putExtra("score", 100);
                getActivity().setResult(android.app.Activity.RESULT_OK, resultIntent);
                getActivity().finish();
            }
        }
    }

    private void markLevelsUpToCurrent(boolean isLastLevel, int chapterNumber) {
        if (getActivity() == null) return;

        new Thread(() -> {
            try {
                int completedCount = SupabaseJavaService.getCompletedLevelsCount(chapterNumber);
                if (completedCount >= levelNumber) {
                    Log.d("ProgressDebug", "Level " + levelNumber + " already completed — skipping update.");
                    // Still trigger UI update for real-time refresh
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (progressViewModel != null && isAdded()) {
                                progressViewModel.updateChapterProgress(chapterNumber, levelNumber);
                                progressViewModel.triggerProgressUpdate();
                            }
                        });
                    }
                    return;
                }

                boolean success = SupabaseJavaService.updateUserProgress(chapterNumber, levelNumber);

                // ✅ Check again before updating UI
                if (!isAdded()) {
                    Log.w("ProgressDebug", "Fragment detached — skipping UI update for level " + levelNumber);
                    return;
                }

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                    // ✅ Double-check in case activity was destroyed mid-call
                    if (!isAdded() || getActivity() == null) {
                        Log.w("ProgressDebug", "Fragment not attached during UI update — safely skipped.");
                        return;
                    }

                    if (success) {
                        if (progressViewModel != null) {
                            // Update progress immediately for real-time UI updates
                            progressViewModel.updateChapterProgress(chapterNumber, levelNumber);
                            updateUserAchievements(chapterNumber, levelNumber);
                            
                            // Trigger progress update event to notify all observers (including HomePage)
                            progressViewModel.triggerProgressUpdate();
                            
                            // Refresh all data from Supabase in background
                            progressViewModel.refreshAllData();
                            
                            Log.d("ProgressDebug", "✅ Progress updated and observers notified for Chapter " + chapterNumber + ", Level " + levelNumber);
                        }
                    }

                    // Safe result set with completed level data
                    if (getActivity() != null) {
                        Intent resultIntent = new Intent();
                        resultIntent.putExtra("completed_level", levelNumber);
                        resultIntent.putExtra("level_completed", true);
                        resultIntent.putExtra("score", 100); // Default score for video completion
                        getActivity().setResult(android.app.Activity.RESULT_OK, resultIntent);
                        
                        Log.d("ProgressDebug", "Result intent set with level " + levelNumber + " completed");
                    }
                });
            }

            } catch (Exception e) {
                Log.e("ProgressDebug", "Error in markLevelsUpToCurrent: " + e.getMessage());
            }
        }).start();
    }

    private void goToLevel(int nextLevelNumber) {
        Fragment fragment = LessonFragment.newInstance(chapterId, nextLevelNumber);
        getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    private int extractChapterNumber(String chapterId) {
        try {
            if (chapterId != null && chapterId.startsWith("chapter")) {
                return Integer.parseInt(chapterId.replace("chapter", ""));
            }
        } catch (NumberFormatException e) {
            Log.e("LessonDebug", "Error parsing chapter number from " + chapterId);
        }
        return 1;
    }


    private LessonData.Lesson[] getLessonsForChapter() {
        switch (chapterId) {
            case "chapter1": return LessonData.CHAPTER1;
            case "chapter2": return LessonData.CHAPTER2;
            case "chapter3": return LessonData.CHAPTER3;
            case "chapter4": return LessonData.CHAPTER4;
            case "chapter5": return LessonData.CHAPTER5;
            default:
                Log.e("LessonDebug", "Unknown chapter: " + chapterId);
                return null;
        }
    }

    private LessonDescriptionData.LessonDescription[] getDescriptionsForChapter() {
        switch (chapterId) {
            case "chapter1": return LessonDescriptionData.CHAPTER1;
            case "chapter2": return LessonDescriptionData.CHAPTER2;
            case "chapter3": return LessonDescriptionData.CHAPTER3;
            case "chapter4": return LessonDescriptionData.CHAPTER4;
            case "chapter5": return LessonDescriptionData.CHAPTER5;
            default:
                Log.e("LessonDebug", "Unknown chapter for description: " + chapterId);
                return new LessonDescriptionData.LessonDescription[0];
        }
    }


    private void updateUserAchievements(int chapterNumber, int levelNumber) {
        if (progressViewModel == null) return;

        ProgressViewModel.UserProfile profile = progressViewModel.getUserProfile().getValue();
        if (profile != null) {
            ProgressViewModel.ChapterProgress chapterProgress = progressViewModel.getChapterProgressObject(chapterNumber);
            if (chapterProgress != null && chapterProgress.getCompletedLevels() >= getTotalLevelsInChapter()) {
                profile.setTotalChaptersCompleted(profile.getTotalChaptersCompleted() + 1);
                // Don't auto-mark quiz as completed - user must take the quiz
            }

            if (levelNumber % 5 == 0) {
                profile.setTotalQuizzesPassed(profile.getTotalQuizzesPassed() + 1);
                progressViewModel.markQuizCompleted(chapterNumber);
            }

            progressViewModel.updateUserProfile(profile);
        }
    }

    private int getTotalLevelsInChapter() {
        LessonData.Lesson[] lessons = getLessonsForChapter();
        return lessons != null ? lessons.length : 0;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        progressHandler.removeCallbacksAndMessages(null);
    }
}
