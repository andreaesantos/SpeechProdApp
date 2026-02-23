package dev.andrea.speechprod

/**
 * Represents the different states of the experiment.
 */
enum class ExperimentState {
    IDLE,             // Initial state
    TRIAL_VIDEO,      // Showing video stimulus
    SPEECH_PRODUCTION, // Recording participant's speech
    EXPERIMENT_END,   // Experiment completed
    ERROR_RECOVERY    // Error recovery state
}
