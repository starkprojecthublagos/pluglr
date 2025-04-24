class AudioQueueManager {
    constructor({ pitchFactor = 1.0, gainValue = 1.0 } = {}) {
        this.audioQueue = [];
        this.isPlaying = false;
        this.pitchFactor = pitchFactor;

        this.audioContext = new AudioContext({ sampleRate: 16000 });
        
        // Add gain node for volume control
        this.gainNode = this.audioContext.createGain();
        this.gainNode.gain.value = gainValue;

        // Add a dynamics compressor for smoother levels (optional)
        this.compressor = this.audioContext.createDynamicsCompressor();
        this.compressor.threshold.setValueAtTime(-50, this.audioContext.currentTime);
        this.compressor.knee.setValueAtTime(40, this.audioContext.currentTime);
        this.compressor.ratio.setValueAtTime(12, this.audioContext.currentTime);
        this.compressor.attack.setValueAtTime(0, this.audioContext.currentTime);
        this.compressor.release.setValueAtTime(0.25, this.audioContext.currentTime);

        // Connect filter chain: compressor → gain → output
        this.compressor.connect(this.gainNode);
        this.gainNode.connect(this.audioContext.destination);
    }

    addAudioToQueue(audioData) {
        this.audioQueue.push(audioData);
        this.playNext();
    }

    async playNext() {
        if (this.isPlaying || this.audioQueue.length === 0) return;

        this.isPlaying = true;
        const buffer = this.audioQueue.shift();
        await this.playAudio(buffer);
        this.isPlaying = false;
        this.playNext();
    }

    playAudio(int16Array) {
        return new Promise((resolve) => {
            const float32Array = new Float32Array(int16Array.length);
            for (let i = 0; i < int16Array.length; i++) {
                float32Array[i] = int16Array[i] / 0x7FFF;
            }

            const audioBuffer = this.audioContext.createBuffer(
                1, 
                float32Array.length, 
                this.audioContext.sampleRate
            );
            audioBuffer.copyToChannel(float32Array, 0);

            const source = this.audioContext.createBufferSource();
            source.buffer = audioBuffer;
            source.playbackRate.value = this.pitchFactor;

            // Connect to the effects chain
            source.connect(this.compressor);

            source.onended = resolve;
            source.start();
        });
    }

    setGain(value) {
        if (this.gainNode) {
            this.gainNode.gain.value = value;
        }
    }

    setPitch(value) {
        this.pitchFactor = value;
    }

    async stop() {
        await this.audioContext.close();
    }
}

export default AudioQueueManager;
