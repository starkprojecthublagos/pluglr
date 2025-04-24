

 // Global variables for audio control
        let floatBuffer = [];
        let isHostSpeaking = false;
        let isCohostSpeaking = false;
        let currentStream = null;
        let audioProcessor = null;
        let gainNode = null;
        let socket, mediaRecorder, cameraStream, liveId;
        let isScreenSharing = false;
        let participants = {};
        let userId = "1";
        let audioContext = null;
        let processor = null;
        let operation =true;
        let cameraVideo = document.getElementById("cameraPreview");
        let isCoHostJoin = false;
        let coHostUserId = null;
        let coHostUsername = null;
        let token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlcyI6WyJVU0VSIl0sInVzZXJfaWQiOjEsImV4cCI6MTc0NTQ4NDMxMywiaWF0IjoxNzQ1Mzk3OTEzfQ.-z1_lEPR7iJ9XcOMe8pM9Hr7z1BZKX31wsr4UVwdncU"
        document.getElementById("start").addEventListener("click", async () => {
            socket = new WebSocket(`wss://apps.plulgr.com/ws/stream/start/live/${userId}?token=${encodeURIComponent(token)}`);
            socket.onopen = () => console.log("WebSocket connected!");

            socket.onmessage = async (event) => {
                if (typeof event.data === "string") {
                    try {
                        const data = JSON.parse(event.data);

                        if (data.type === "stream_link") {
                            await startAudioStream();
                        }
                        // Handle co-host speaking status
                        else if (data.type === "cohost_audio_status") {
                            isCohostSpeaking = data.isSpeaking;
                            toggleHostMic(data.isSpeaking);
                            
                            // Optional: Visual feedback
                            updateSpeakingIndicators();
                        }
                        handleEventData(data);
                    } catch (error) {
                        console.error("Error parsing WebSocket message:", error);
                    }
                }
                else if (event.data instanceof ArrayBuffer) {

                }
            };
            document.getElementById("start").disabled = true;
            document.getElementById("stop").disabled = false;
            document.getElementById("toggleStream").disabled = false;
        });

        async function startAudioStream() {
            try {
                stopActiveSockets();

                const stream = await navigator.mediaDevices.getUserMedia({
                    audio: {
                        echoCancellation: true,
                        noiseSuppression: true,
                        autoGainControl: true
                    }
                });

                const AudioContextClass = window.AudioContext || window.webkitAudioContext;
                const audioContext = new AudioContextClass({ sampleRate: 16000 });
                const source = audioContext.createMediaStreamSource(stream);
                const gainNode = audioContext.createGain();
                gainNode.gain.value = 1.1;  // Gain for feedback (monitoring)

                // --- Use AudioWorklet if supported ---
                if (audioContext.audioWorklet) {
                    try {
                        await audioContext.audioWorklet.addModule('/js/processor.js');

                        const workletNode = new AudioWorkletNode(audioContext, 'audio-sender-processor');

                        workletNode.port.onmessage = (event) => {
                            if (!isCohostSpeaking && socket?.readyState === WebSocket.OPEN) {
                                socket.send(event.data);
                            }
                        };

                        // Connect for monitoring and processing
                        source.connect(gainNode);
                        gainNode.connect(workletNode);
                        workletNode.connect(audioContext.destination);  // Play back your voice

                        audioProcessor = workletNode;
                        return;
                    } catch (err) {
                        console.warn('AudioWorklet module load failed, using fallback:', err);
                    }
                }

                // --- Fallback: ScriptProcessorNode (deprecated but safe fallback) ---
                const processor = audioContext.createScriptProcessor(1024, 1, 1);
                let floatBuffer = [];

                processor.onaudioprocess = (event) => {
                    const input = event.inputBuffer.getChannelData(0);
                    floatBuffer.push(...input);

                    // Hear yourself by routing the input to the output
                    const output = event.outputBuffer.getChannelData(0);
                    for (let i = 0; i < input.length; i++) {
                        output[i] = input[i] * 1.1; // Amplify to hear yourself
                    }

                    // Processing audio (normalization, silence detection)
                    while (floatBuffer.length >= 960) {
                        let frame = floatBuffer.slice(0, 960);
                        floatBuffer = floatBuffer.slice(960);

                        const isSilent = frame.every(sample => Math.abs(sample) < 0.01);
                        if (isSilent) continue;

                        const maxAmp = Math.max(...frame.map(Math.abs));
                        if (maxAmp > 0 && maxAmp < 0.9) {
                            frame = frame.map(sample => sample * (0.9 / maxAmp));
                        }

                        const int16 = new Int16Array(960);
                        for (let i = 0; i < 960; i++) {
                            int16[i] = Math.max(-1, Math.min(1, frame[i])) * 32767;
                        }

                        if (!isCohostSpeaking && socket?.readyState === WebSocket.OPEN) {
                            socket.send(int16.buffer);
                        }
                    }
                };

                source.connect(gainNode);
                gainNode.connect(processor);
                processor.connect(audioContext.destination);

                audioProcessor = processor;
            } catch (error) {
                console.error("Audio stream error:", error);
            }
        }

        // Function to mute/unmute host mic
        function toggleHostMic(mute) {
            if (gainNode) {
                gainNode.gain.value = mute ? 0 : 1.2;
            }
            if (mute) {
                console.log("Host mic muted");
            } else {
                console.log("Host mic unmuted");
            }
        }

        // UI function to show who's speaking
        function updateSpeakingIndicators() {
            const hostIndicator = document.getElementById('host-speaking-indicator');
            const cohostIndicator = document.getElementById('cohost-speaking-indicator');
            
            if (isHostSpeaking) {
                hostIndicator.style.display = 'block';
                cohostIndicator.style.display = 'none';
            } else if (isCohostSpeaking) {
                hostIndicator.style.display = 'none';
                cohostIndicator.style.display = 'block';
            } else {
                hostIndicator.style.display = 'none';
                cohostIndicator.style.display = 'none';
            }
        }

        async function startScreenSharing() {
            stopActiveSockets();

            try {
               
                if(operation){
                    // Get screen sharing stream (includes video & optional system audio)
                    currentStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true });

                    // Capture microphone audio separately
                    const micStream = await navigator.mediaDevices.getUserMedia({ audio: { sampleRate: 16000, channelCount: 1 } });

                    if (micStream.getAudioTracks().length > 0) {
                        currentStream.addTrack(micStream.getAudioTracks()[0]); // Merge mic audio into screen stream
                    }

                    // Set up screen video recording
                    mediaRecorder = new MediaRecorder(currentStream, { mimeType: "video/webm; codecs=vp9" });
                   
                    mediaRecorder.ondataavailable = async (event) => {
                        if (event.data.size > 0) {
                            // Send video data
                            socket.send(event.data);
                        }
                    };

                    mediaRecorder.start(100);
                    isScreenSharing = true;
                    document.getElementById("toggleStream").innerText = "Switch to Audio";

                    // Notify backend that screen sharing started
                    socket.send(JSON.stringify({ type: "switching_to_screen_sharing", stream: "screen" }));

                    // Capture & send raw PCM audio
                    const audioContext = new AudioContext({ sampleRate: 16000 });
                    const micSource = audioContext.createMediaStreamSource(micStream);
                    const processor = audioContext.createScriptProcessor(4096, 1, 1);

                    micSource.connect(processor);
                    processor.connect(audioContext.destination);

                    processor.onaudioprocess = (event) => {
                        const inputBuffer = event.inputBuffer.getChannelData(0); // Mono PCM
                        const int16Array = new Int16Array(inputBuffer.length);

                        for (let i = 0; i < inputBuffer.length; i++) {
                            int16Array[i] = inputBuffer[i] * 32768; // Convert float to int16
                        }

                        if (socket && socket.readyState === WebSocket.OPEN) {
                                socket.send(int16Array.buffer);
                        } else {
                            return;
                        }
                    };

                    // Start camera feed overlay
                    cameraStream = await navigator.mediaDevices.getUserMedia({ video: true });
                    cameraVideo.srcObject = cameraStream;
                    cameraVideo.style.display = "block";
                    makeDraggable(cameraVideo);
                }
            } catch (error) {
                console.error("Error starting screen sharing:", error);
            }
        }

        function stopActiveSockets() {
            if (mediaRecorder) {
                mediaRecorder.stop();
            }
            if (currentStream) {
                currentStream.getTracks().forEach(track => track.stop());
            }
            if (cameraStream) {
                cameraStream.getTracks().forEach(track => track.stop());
            }
            cameraVideo.style.display = "none";
 
        }

        function closeConnections() {
            if (processor) {
                processor.disconnect();
                processor.onaudioprocess = null;
                processor = null;
            }

            if (socket) {
                if (socket.readyState === WebSocket.OPEN) {
                    socket.send(JSON.stringify({ type: "stream_ended", event_id: liveId }));
                }
            }

            if (audioContext) {
                // audioContext.close().then(() => { audioContext = null; });
                audioContext.close();
                audioContext = null;
            }

            if (mediaRecorder) {
                mediaRecorder.stop();
                mediaRecorder = null;
            }

            if (currentStream) {
                currentStream.getTracks().forEach(track => track.stop());
                currentStream = null;
            }

            if (cameraStream) {
                cameraStream.getTracks().forEach(track => track.stop());
                cameraStream = null;
            }

            cameraVideo.style.display = "none";
                
        }

        function handleEventData(data) {
            if (data.event_id) {
                liveId = data.event_id;
                document.getElementById("event-info").innerHTML = `<p><strong>Live Event Link:</strong> <a href="${data.join_url}" target="_blank">${data.join_url}</a></p>`;
            }

            if (data.type === "participant_list") {
                participants = {};
                data.participants.forEach(participant => {
                    participants[participant.id] = {
                        username: participant.username,
                        is_cohost: participant.is_cohost || false
                    };
                });
                updateParticipantList();
            }

            if (data.type === "participant_count") {
                document.getElementById("participants-count").innerText = `Participants: ${data.count}`;
            }

            // Handle incoming message_broadcast messages
            if (data.type === "chat_message") {
                const messagesDiv = document.getElementById("messages");
                messagesDiv.innerHTML += `<p><strong>${data.username}:</strong> ${data.message}</p>`;
                messagesDiv.scrollTop = messagesDiv.scrollHeight; // Auto-scroll to latest message
            }

            if (data.type == "cohost_joined") {
                isCoHostJoin = true;
                coHostUserId = data.participant_id; 
                coHostUsername =data.username;

                if (participants[data.participant_id]) {
                    updateParticipantList();
                }
                $.jGrowl(data.message, { life: 10000});
            }

            if (data.type === "cohost_left") {
                isCoHostJoin = false;
                coHostUserId = null;
                if (participants[data.user_id]) {
                    participants[data.user_id].is_cohost = false;
                    updateParticipantList();
                }
                $.jGrowl(data.message, { life: 10000});
            }

            if (data.type == "cohost_removed") {
                isCoHostJoin = false;
                 // Ensure the removed co-host ID is cleared
                if (data.participant_id === coHostUserId) {
                    coHostUserId = null;
                }
                $.jGrowl(data.message, { life: 10000});
                updateParticipantList();
            }

            if(data.type === "participant_left"){
                updateParticipantList();
                $.jGrowl(data.message, { life: 10000});
            }

            if(data.type === "error"){
                $.jGrowl(data.details, { life: 10000});
            }
            
            if(data.type === "stream_ended"){
                socket.onclose = null;
                socket.onerror = null;
                socket.close();
                socket = null;
            }
        }

        function updateParticipantList() {
            const list = document.getElementById("participant-list");
            list.innerHTML = "";

            Object.entries(participants).forEach(([id, participant]) => {
                
                const li = document.createElement("li");
                li.textContent = participant.username;
            
                if (id === coHostUserId) {
                    const removeCohostButton = document.createElement("button");
                    removeCohostButton.textContent = "Remove Co-host";
                    removeCohostButton.style.marginLeft = "10px";
                    removeCohostButton.classList.add("btn", "btn-danger", "btn-xs");
                    removeCohostButton.onclick = () => removeCohost(id, liveId);
                    li.appendChild(removeCohostButton);
                } else {
                    const inviteButton = document.createElement("button");
                    inviteButton.classList.add("btn", "btn-secondary", "btn-xs");
                    inviteButton.textContent = "Invite";
                    inviteButton.style.marginLeft = "10px";
                    inviteButton.onclick = () => sendInvite(id, liveId);
                    li.appendChild(inviteButton);
                }

                list.appendChild(li);
            });

            document.getElementById("participants-count").innerText = `Participants: ${Object.keys(participants).length}`;
        }

        function sendInvite(userId, event_id) {
            if (socket) {
                socket.send(JSON.stringify({ type: "invite_cohost", "user_id": userId, "event_id": event_id}));
            }
        }

        function sendMessage(userId) {
            const message = document.getElementById("messageInput").value;
            if (message && socket) {
                socket.send(JSON.stringify({ type: "broadcast_message", message, "user_id":userId, "event_id":liveId}));
                document.getElementById("messageInput").value = "";
            }
        }
        
         
        document.getElementById("stop").addEventListener("click", () => {
           closeConnections();
            document.getElementById("start").disabled = false;
            document.getElementById("stop").disabled = true;
            document.getElementById("toggleStream").disabled = true;
        });

        // Make video draggable
        function makeDraggable(element) {
            let posX = 0, posY = 0, lastX = 0, lastY = 0;
            element.onmousedown = dragMouseDown;

            function dragMouseDown(e) {
                e.preventDefault();
                lastX = e.clientX;
                lastY = e.clientY;
                document.onmouseup = closeDrag;
                document.onmousemove = dragElement;
            }

            function dragElement(e) {
                e.preventDefault();
                posX = lastX - e.clientX;
                posY = lastY - e.clientY;
                lastX = e.clientX;
                lastY = e.clientY;
                element.style.top = (element.offsetTop - posY) + "px";
                element.style.left = (element.offsetLeft - posX) + "px";
            }

            function closeDrag() {
                document.onmouseup = null;
                document.onmousemove = null;
            }
        }
        
        function removeCohost(userId, event_id) {
            if (socket) {
                socket.send(JSON.stringify({ type: "remove_cohost", "user_id": userId, "event_id": event_id }));
            }
        }

        document.getElementById("toggleStream").addEventListener("click", async () => {
            if (isScreenSharing) {
              
                await startAudioStream();
                isScreenSharing = false;
                document.getElementById("toggleStream").innerText = "Switch to Screen Sharing";
                socket.send(JSON.stringify({ type: "switching_to_audio", stream: "audio" }));
            } else {
                
                await startScreenSharing();
                isScreenSharing = true;
                document.getElementById("toggleStream").innerText = "Switch to Audio";
                socket.send(JSON.stringify({ type: "switching_to_screen_sharing", stream: "screen" }));
                
            }
        });
    
        document.getElementById("sendMessageBtn").addEventListener("click", () => {
            sendMessage(userId);
        });

        updateSpeakingIndicators();