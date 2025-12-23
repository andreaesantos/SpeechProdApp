package dev.andrea.perroquet

/**
 * Represents the different states of the experiment.
 */
enum class ExperimentState {
    IDLE,             // Initial state
    BLOCK_START,      // Beginning of a block
    TRIAL_VIDEO,      // Showing video stimulus
    FIXATION_DELAY,   // Delay between video and speech recording
    SPEECH_RECORDING, // Recording participant's speech
    BLOCK_END,        // End of a block
    EXPERIMENT_END,   // Experiment completed
    ERROR_RECOVERY    // Error recovery state
}
