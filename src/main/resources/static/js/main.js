"use strict";


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



// Global variables for audio control
   let audioQueueManager = null;  
    audioQueueManager = new AudioQueueManager(0.8);
    let isSpeaking = false;
    let currentStream = null;
    let mediaStreamSource = null;
    let audioProcessor = null;
    let isCohost = false;
    let socket, mediaRecorder
    const mediaSource = new MediaSource();
    let sourceBuffer = null;
    let mediaQueue = [];
        
    let audioContext = new (window.AudioContext || window.webkitAudioContext)();
    let audioQueue = [];
    let participant_user_id = ''; 
    let username = '';
    let lastEventType = null;
    let room_id = null;
    
    // Set up MediaSource for screen sharing
    mediaSource.addEventListener('sourceopen', () => {
        sourceBuffer = mediaSource.addSourceBuffer('video/webm; codecs=vp9');
    });
     let screenVideo = document.getElementById("screenShareVideo"); 
    screenVideo.src = URL.createObjectURL(mediaSource);
    const messagesDiv = document.getElementById("messages");
    const acceptBtn = document.getElementById("acceptCohost");
    const declineBtn = document.getElementById("declineCohost");
    const leaveBtn = document.getElementById("leaveCohost");
    const list = document.getElementById("participant-list");
    let leaveButton = document.getElementById("leave");
    
    function getUserIdFromWsUrl(wsUrl) {
        const urlParts = wsUrl.split("/");
        var data = ([urlParts[10], urlParts[9], urlParts[8]]);
        return data; 
    }

    document.getElementById("connect").addEventListener("click", () => {
        const wsUrl = document.getElementById("wsUrl").value;
        const userDetails = getUserIdFromWsUrl(wsUrl);
        participant_user_id = userDetails[0];
        username = userDetails[1];
        room_id = userDetails[2];
        const manager = new AudioQueueManager(0.8);
        if (!wsUrl) {
            $.jGrowl("Please enter a WebSocket URL", { life: 10000});
            return;
        }

        socket = new WebSocket(wsUrl);
        socket.binaryType = "blob";
        socket.onopen = () => {
            showLeaveButton();
        };

        // WebSocket message handler for user/co-host side
        socket.onmessage = async (event) => {
            if (typeof event.data === "string") {
                try {
                    const data = JSON.parse(event.data);
                    handleEventData(data);
                } catch (error) {
                    console.error("Error parsing WebSocket message:", error);
                }
            } else if (event.data instanceof Blob) {
                const buffer  = await event.data.arrayBuffer();
                handleIncomingAudio(buffer);
            } else if (event.data instanceof ArrayBuffer) {
               handleIncomingAudio(event.data);
            }
        };
        
        socket.onclose = () => {
            hideLeaveButton(); 
        };

        socket.onerror = (error) => {
            $.jGrowl("WebSocket error. Please try again", { life: 10000});
        };
    });

    


    // Function to show the "Leave Live" button
    function showLeaveButton() {
        leaveButton.addEventListener("click", leaveLiveStream);
        leaveButton.classList.remove("d-none");
    }

    // Function to hide the "Leave Live" button
    function hideLeaveButton() {
        leaveButton.classList.add("d-none");
    }

    // Function to handle leaving the live stream
    function leaveLiveStream() {
        const data  = {
            "type": "leave_room",
            "event_id": room_id,
            "user_id": participant_user_id
        };
        sendWebSocketMessage(data);
        if (socket) {
            socket.close();
        }
        hideLeaveButton();
        $.jGrowl("You have left the live stream.", { life: 10000});
        $.jGrowl("Disconnected from WebSocket", { life: 20000})
    }

    screenVideo.style.display = "none";

    function handleEventData(data) {
        if (data.type === "participant_list") {
            list.innerHTML = "";
            data.participants.forEach(participant => {
                const li = document.createElement("li");
                li.textContent = participant.username;
                list.appendChild(li);
            });
            document.getElementById("participants-count").innerText = `Participants: ${data.participants.length}`;
        }

        if (data.type === "chat_message") {
            messagesDiv.innerHTML += `<p><strong>${data.username}:</strong> ${data.message}</p>`;
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
        }

        if (data.type === "stream_ended") {
            screenVideo.style.display = "none";
            messagesDiv.innerHTML = ""; 
            list.innerHTML = "";
            $.jGrowl(data.message, { life: 10000});
            if (audioContext) audioContext.close(); // Stop audio context
        }

        if (data.mode === "switching_to_audio") {
            switchToAudioMode();
        } else if (data.mode === "switching_to_screen_sharing") {
            switchToScreenSharingMode();
        }
        
        if (data.type === "chat_history") {
            if (Array.isArray(data.messages)) {
                data.messages.forEach((message) => {
                    if (message.type === "chat_message") {
                        // Process chat_message type here
                        messagesDiv.innerHTML += `<p><strong>${message.username}:</strong> ${message.message}</p>`;
                        messagesDiv.scrollTop = messagesDiv.scrollHeight;
                    }
                });
            }
        }

        // Handle cohost invite
        if (data.type === "cohost_invite") {
            showCohostInviteModal(data.message);
        }

        if (data.type === "cohost_left") {
            $.jGrowl(data.message, { life: 10000});
        }

        if(data.type ==="cohost_joined"){
            $.jGrowl(data.message, { life: 10000});
        }
        
        if (data.type === "cohost_removed") {
            // Reset button states
            disableMicrophone();
            leaveBtn.classList.add("d-none");
            $.jGrowl(data.message, { life: 10000});
        }
        
        if(data.type === "participant_left"){
            updateParticipantList();
            $.jGrowl(data.message, { life: 10000});
        }

        if(data.type === "error"){
            $.jGrowl(data.details, { life: 10000});
        }
    }

    // ** Switch to Audio Mode **
    function switchToAudioMode() {
        screenVideo.style.display = "none";
        console.log("Switching to Audio Stream...");

        if (mediaSource) {
            mediaSource.endOfStream();
            mediaSource = null;
            sourceBuffer = null;
            mediaQueue = [];
        }
    }

    // ** Switch to Screen Sharing Mode **
    function switchToScreenSharingMode() {
        screenVideo.style.display = "block";
        if (audioContext) {
            audioContext.close();
            audioContext = null;
        }
    }

    function sendMessage() {
        const message = document.getElementById("messageInput").value;
        if (message && socket) {
            socket.send(JSON.stringify({ type: "broadcast_message", message,  "event_id": room_id, "user_id": participant_user_id }));
            document.getElementById("messageInput").value = "";
        }
    }

    async function playRawAudio(audioBlob) {
        try {
            const arrayBuffer = await audioBlob.arrayBuffer();
            const view = new DataView(arrayBuffer);

            // 🔹 Set audio format (modify based on WebSocket server settings)
            const sampleRate = 48000; // Try 16000, 32000, 44100, or check WebSocket server settings
            const numChannels = 1; // Change to 2 for stereo
            const bytesPerSample = 2; // 16-bit PCM

            const numSamples = arrayBuffer.byteLength / bytesPerSample / numChannels;
            const audioBuffer = audioContext.createBuffer(numChannels, numSamples, sampleRate);

            // Convert PCM to Float32Array
            for (let channel = 0; channel < numChannels; channel++) {
                const channelData = audioBuffer.getChannelData(channel);
                for (let i = 0; i < numSamples; i++) {
                    const sampleIndex = i * numChannels + channel;
                    const sample = view.getInt16(sampleIndex * bytesPerSample, true);
                    channelData[i] = sample / 32768.0; // Normalize to [-1, 1]
                }
            }

            // Queue audio to avoid gaps
            audioQueue.push(audioBuffer);
            if (audioQueue.length === 1) {
                playNextBuffer();
            }

        } catch (error) {
            console.error("Error processing raw audio:", error);
        }
    }
    
    // Play next buffer in the queue
    function playNextBuffer() {
        if (audioQueue.length === 0) return;

        const source = audioContext.createBufferSource();
        source.buffer = audioQueue.shift();
        source.connect(audioContext.destination);

        source.onended = playNextBuffer; 
        source.start();
    }

    // Modified co-host invite modal with better audio control
    function showCohostInviteModal(message) {
        $.jGrowl(message, { life: 10000 });
        document.getElementById("cohostInviteMessage").innerText = message;
        const modal = new bootstrap.Modal(document.getElementById("cohostInviteModal"));
        modal.show();

        // Reset button states
        leaveBtn.classList.add("d-none");

        // Handle Accept
        acceptBtn.onclick = function() {
            const data = {
                "type": "accept_cohost",
                "event_id": room_id,
                "user_id": participant_user_id
            };
            enableMicrophone();
            sendWebSocketMessage(data);
            leaveBtn.classList.remove("d-none");
            modal.hide();
        };

        // Handle Decline
        declineBtn.onclick = function() {
            sendWebSocketMessage({ type: "cohost_reject" });
            modal.hide();
        };

        // Handle Leaving as Co-host
        leaveBtn.onclick = function() {
            disableMicrophone();
            sendWebSocketMessage({
                type: "leave_cohost",
                "event_id": room_id,
                "user_id": participant_user_id
            });
            leaveBtn.classList.add("d-none");
        };
    }

    // Enhanced microphone control functions
    async function enableMicrophone() {
        try {
            disableMicrophone();
            
            // Get high-quality audio input
            currentStream = await navigator.mediaDevices.getUserMedia({ 
                audio: {
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: false, // Better for manual control
                    sampleRate: 16000,
                    channelCount: 1
                }
            });

            audioContext = new AudioContext({ sampleRate: 16000 });
            mediaStreamSource = audioContext.createMediaStreamSource(currentStream);
            audioProcessor = audioContext.createScriptProcessor(4096, 1, 1);

            // Voice Activity Detection (VAD) variables
            let silenceThreshold = 0.02;
            let silenceDuration = 0;
            let isSilent = false;
            let consecutiveSilentFrames = 0;
            const requiredSilentFrames = 5; // ~100ms at 4096 buffer size

            audioProcessor.onaudioprocess = (event) => {
                const inputData = event.inputBuffer.getChannelData(0);
                const int16Array = new Int16Array(inputData.length);
                
                // Detect if user is speaking
                let currentIsSilent = true;
                let maxSample = 0;
                for (let i = 0; i < inputData.length; i++) {
                    const sample = Math.abs(inputData[i]);
                    if (sample > silenceThreshold) {
                        currentIsSilent = false;
                    }
                    if (sample > maxSample) {
                        maxSample = sample;
                    }
                    int16Array[i] = inputData[i] * 32767;
                }

                // Voice activity state machine
                if (!currentIsSilent) {
                    consecutiveSilentFrames = 0;
                    if (!isSpeaking) {
                        isSpeaking = true;
                        sendSpeakingStatus(true);
                    }
                } else {
                    consecutiveSilentFrames++;
                    if (consecutiveSilentFrames > requiredSilentFrames && isSpeaking) {
                        isSpeaking = false;
                        sendSpeakingStatus(false);
                    }
                }

                // Only send audio when speaking
                if (isSpeaking && socket.readyState === WebSocket.OPEN) {
                    socket.send(int16Array.buffer);
                }
            };

            mediaStreamSource.connect(audioProcessor);
            audioProcessor.connect(audioContext.destination);
            isCohost = true;
            
            // UI feedback
            updateSpeakingUI();
            console.log("Microphone enabled as co-host");
        } catch (error) {
            console.error("Error enabling microphone:", error);
        }
    }

    // Disable Microphone (When user leaves as a cohost)
    function disableMicrophone() {
        if (audioProcessor) {
            audioProcessor.disconnect();
            audioProcessor = null;
        }
        if (mediaStreamSource) {
            mediaStreamSource.disconnect();
            mediaStreamSource = null;
        }
        if (audioContext) {
            audioContext.close().catch(console.error);
            audioContext = null;
        }
        if (currentStream) {
            currentStream.getTracks().forEach(track => track.stop());
            currentStream = null;
        }
        
        if (isSpeaking) {
            isSpeaking = false;
            sendSpeakingStatus(false);
        }
        
        isCohost = false;
        updateSpeakingUI();
        console.log("Microphone disabled");
    }

    function sendWebSocketMessage(message) {
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify(message));
        }
    }

    function toggleMicrophone(enable) {
        if (enable) {
            enableMicrophone();
        } else {
            disableMicrophone();
        }
    }

    function sendSpeakingStatus(speaking) {
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify({
                "type": "cohost_audio_status",
                "isSpeaking": speaking,
                "user_id": participant_user_id,
                "event_id":room_id
            }));
        }
    }

    function updateSpeakingUI() {
        const micIndicator = document.getElementById('mic-status-indicator');
        if (isCohost) {
            micIndicator.textContent = isSpeaking ? "🎤 Speaking" : "🎤 Muted";
            micIndicator.style.color = isSpeaking ? "#4CAF50" : "#FF5722";
        } else {
            micIndicator.textContent = "🎤 Off";
            micIndicator.style.color = "#9E9E9E";
        }
    }


    function handleIncomingAudio(buffer) {
        const int16Array = new Int16Array(buffer);
        audioQueueManager.addAudioToQueue(int16Array);
    }

    document.getElementById("sendMessageBtn").addEventListener("click", () => {
        sendMessage();
    });