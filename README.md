# Speech Production Experiment App

Forked and adapted from: https://github.com/lucyzmf/MomentsInTime

An Android application for conducting cognitive neuroscience experiments that involve video stimuli and speech recording.

## Overview

This application is designed for researchers conducting experiments where participants view video stimuli and provide verbal responses. The app includes features for:

- Dynamic video resource scanning
- Precise timing of experimental trials
- Audio recording of participant responses
- Event logging with timestamps
- USB trigger output for EEG/fMRI synchronization
- System monitoring for battery and storage

## Features

### Experiment Flow

1. **Participant Setup**: Enter participant ID and date
2. **Instructions**: View experiment instructions
3. **Blocks and Trials**: Multiple blocks with configurable trials per block
4. **Video Stimuli**: Automatically scans for available videos in resources
5. **Fixation Cross**: Configurable delay between video and speech recording
6. **Speech Recording**: Records participant's verbal response
7. **Data Collection**: Saves audio recordings and detailed event logs

### Technical Features

- **Dynamic Video Loading**: Automatically scans and uses videos from the raw resources directory
- **Fallback Mechanisms**: Handles missing videos gracefully
- **Event Logging**: Detailed timestamped logs of all experiment events
- **USB Trigger Output**: Sends trigger codes to external recording equipment
- **Battery Monitoring**: Warns about low battery levels
- **Error Recovery**: Handles and recovers from errors during the experiment
- **Full Screen Mode**: Distraction-free experiment environment

## Setup and Configuration

### Adding Videos

Place video files in the `app/src/main/res/raw/` directory with names like `video1.mp4`, `video2.mp4`, etc. The app will automatically scan and use these videos.

### Experiment Configuration

Modify the `ExperimentConfig.kt` file to change:

- Number of blocks
- Trials per block
- Speech recording duration
- Fixation cross duration

### USB Trigger Configuration

The app sends trigger codes via USB serial connection to external recording equipment. Configure the baud rate and trigger codes in `SerialPortHelper.kt`.

## Development

### Project Structure

- `BaseExperimentActivity.kt`: Core experiment logic and state management
- `ExperimentActivity.kt`: UI implementation and user interaction
- `AudioRecorder.kt`: Audio recording functionality
- `EventLogger.kt`: Event logging system
- `SerialPortHelper.kt`: USB communication for triggers
- `VideoResourceScanner.kt`: Dynamic video resource scanning

### Adding New Features

To extend the experiment with new features:

1. Add new states to `ExperimentState.kt` if needed
2. Implement state handling in `BaseExperimentActivity.onStateChanged()`
3. Update UI in `ExperimentActivity.updateUI()`

## Troubleshooting

### Common Issues

- **Permission Denied**: Ensure the app has microphone and storage permissions
- **USB Connection Failed**: Check USB cable and device compatibility
- **Video Not Found**: Ensure videos are properly placed in the raw resources directory

### Logs

The app saves detailed logs in the external storage directory under `logs/`. These logs can be used to diagnose issues and analyze experiment timing.

