let currentUserType = '';
let bpmInterval = null;
let ecgAnimFrame = null;

function navigateTo(screen, userType) {
  if (userType) currentUserType = userType;

  document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
  const target = document.getElementById('screen-' + screen);
  if (target) {
    target.classList.add('active');
  }

  if (screen === 'patient') {
    startHeartMonitor();
    startECG();
  } else {
    stopHeartMonitor();
    stopECG();
  }
}

function togglePassword() {
  const input = document.getElementById('password-input');
  input.type = input.type === 'password' ? 'text' : 'password';
}

function handleLogin() {
  if (currentUserType === 'patient') {
    navigateTo('patient');
  } else {
    navigateTo('caregiver');
  }
}

function startHeartMonitor() {
  let bpm = 78;
  const bpmDisplay = document.getElementById('bpm-display');
  const statusDisplay = document.getElementById('status-display');

  bpmInterval = setInterval(() => {
    const change = Math.floor(Math.random() * 5) - 2;
    bpm = Math.max(60, Math.min(100, bpm + change));
    bpmDisplay.textContent = bpm;

    statusDisplay.className = '';
    if (bpm < 60 || bpm > 100) {
      statusDisplay.textContent = 'Atenção';
      statusDisplay.className = 'status-warning';
    } else if (bpm > 90) {
      statusDisplay.textContent = 'Elevado';
      statusDisplay.className = 'status-warning';
    } else {
      statusDisplay.textContent = 'Normal';
      statusDisplay.className = 'status-normal';
    }
  }, 1500);
}

function stopHeartMonitor() {
  if (bpmInterval) {
    clearInterval(bpmInterval);
    bpmInterval = null;
  }
}

function startECG() {
  const canvas = document.getElementById('ecg-canvas');
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  const w = canvas.width;
  const h = canvas.height;
  let offset = 0;

  function drawECG() {
    ctx.fillStyle = 'rgba(13, 17, 23, 0.3)';
    ctx.fillRect(0, 0, w, h);

    ctx.strokeStyle = '#E10600';
    ctx.lineWidth = 2;
    ctx.shadowColor = '#E10600';
    ctx.shadowBlur = 4;
    ctx.beginPath();

    for (let x = 0; x < w; x++) {
      const pos = (x + offset) % 160;
      let y = h / 2;

      if (pos > 60 && pos < 65) {
        y = h / 2 - 5;
      } else if (pos >= 65 && pos < 70) {
        y = h / 2 + 5;
      } else if (pos >= 70 && pos < 75) {
        y = h / 2 - 35;
      } else if (pos >= 75 && pos < 80) {
        y = h / 2 + 15;
      } else if (pos >= 80 && pos < 90) {
        y = h / 2 - 3 * Math.sin((pos - 80) / 10 * Math.PI);
      }

      if (x === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }

    ctx.stroke();
    ctx.shadowBlur = 0;
    offset += 2;
    ecgAnimFrame = requestAnimationFrame(drawECG);
  }

  drawECG();
}

function stopECG() {
  if (ecgAnimFrame) {
    cancelAnimationFrame(ecgAnimFrame);
    ecgAnimFrame = null;
  }
}

function triggerEmergency() {
  const btn = document.querySelector('.btn-emergency');
  btn.style.background = 'linear-gradient(135deg, #ff4444, #ff0000)';
  btn.textContent = 'ALERTA ENVIADO!';
  btn.style.animation = 'none';

  setTimeout(() => {
    btn.innerHTML = '<span>&#9888;</span> EMERGÊNCIA';
    btn.style.background = 'linear-gradient(135deg, #FF0000, #cc0000)';
  }, 3000);
}

navigateTo('main');
