/**
 * Port of ParentDevicesFeature.kt — TV Control banner, pairing card (paste
 * payload / manual entry; QR camera via BarcodeDetector when available),
 * paired-device list with select/remove.
 */
import { h, field, card, emptyPanel, sectionLabel, statusPill, metaTile, formatTimestamp } from './components.js';
import { pair, selectDevice, removeDevice, store } from '../store.js';
import { parsePairingPayload } from '../pairing.js';

export function renderDevicesTab(state) {
  return h('div', {},
    sectionLabel('TV Control'),
    selectedDeviceBanner(state),
    pairNewTvCard(state),
    sectionLabel('Paired Devices'),
    deviceList(state),
  );
}

function selectedDeviceBanner(state) {
  const device = state.devices.find((d) => d.deviceId === state.selectedDeviceId);
  if (!device) return h('div', {});
  return h('div', { class: 'card navy' },
    h('div', { class: 'row' },
      h('span', { style: { fontSize: '22px' } }, '🛡️'),
      h('div', { class: 'grow' },
        h('div', { style: { fontWeight: 700, fontSize: '16px' } }, device.label),
        h('div', { class: 'small', style: { opacity: 0.72 } }, device.deviceId),
      ),
      statusPill(device.online ? 'Online' : 'Offline', device.online, device.online ? 'ok' : 'bad'),
    ),
  );
}

function pairNewTvCard(state) {
  const payloadInput = h('input', {
    type: 'text', placeholder: 'guardpulse://pair?deviceId=...', 'data-persist-key': 'pair-payload',
  });
  const deviceIdInput = h('input', {
    type: 'text', placeholder: 'e.g. TV-9A8B7C', 'data-persist-key': 'pair-device-id',
  });
  const codeInputs = [0, 1, 2, 3, 4, 5].map((i) => {
    const input = h('input', {
      type: 'text', inputmode: 'numeric', maxLength: 1, 'data-persist-key': `pair-code-${i}`,
    });
    input.addEventListener('input', () => {
      input.value = input.value.replace(/\D/g, '').slice(0, 1);
      if (input.value && i < 5) codeInputs[i + 1].focus();
    });
    return input;
  });

  const statusLine = pairStatusLine(state);
  const scanButton = ('BarcodeDetector' in window)
    ? h('button', { class: 'btn secondary', onClick: () => scanQr(payloadInput) }, 'Scan QR Code')
    : null;

  return card(
    h('div', { class: 'card-title' }, 'Pair New TV'),
    statusLine,
    scanButton,
    h('div', { class: 'row', style: { justifyContent: 'center' } }, h('span', { class: 'small muted' }, 'OR MANUAL ENTRY')),
    field('QR payload', payloadInput),
    field('Device ID', deviceIdInput),
    h('div', { class: 'pair-code-row' },
      h('label', { class: 'small muted', style: { fontWeight: 700, letterSpacing: '1px', textTransform: 'uppercase' } }, '6-Digit Code'),
      h('div', { class: 'code-row' },
        codeInputs[0], codeInputs[1], codeInputs[2],
        h('span', { class: 'muted' }, '-'),
        codeInputs[3], codeInputs[4], codeInputs[5],
      ),
    ),
    h('button', {
      class: 'btn',
      onClick: () => pair(
        payloadInput.value.trim(),
        deviceIdInput.value.trim(),
        codeInputs.map((c) => c.value).join(''),
      ),
    }, 'Connect Manually'),
  );
}

function pairStatusLine(state) {
  const request = state.pairRequest;
  if (!request) return h('div', {});
  const kind = request.status === 'accepted' ? 'ok'
    : request.status === 'pending' ? 'info' : 'bad';
  return h('div', { class: 'row' },
    h('span', { class: `label-chip pill ${kind}` }, `Pairing ${request.status}`),
  );
}

async function scanQr(payloadInput) {
  try {
    const detector = new window.BarcodeDetector({ formats: ['qr_code'] });
    const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
    const video = h('video', { style: { width: '100%', borderRadius: '12px' }, muted: true, playsInline: true });
    video.srcObject = stream;
    video.play();
    const overlayRoot = document.getElementById('overlay-root');
    let stop = false;
    const cancel = h('button', { class: 'btn neutral' }, 'Cancel');
    const overlay = h('div', { class: 'overlay' },
      h('div', { class: 'dialog' },
        h('div', { class: 'title' }, 'Scan the GuardPulse TV pairing QR'),
        video,
        cancel,
      ),
    );
    cancel.addEventListener('click', () => { stop = true; stream.getTracks().forEach((t) => t.stop()); overlay.remove(); });
    overlayRoot.append(overlay);
    const tick = async () => {
      if (stop) return;
      try {
        const codes = await detector.detect(video);
        if (codes.length > 0) {
          const payload = codes[0].rawValue;
          stop = true;
          stream.getTracks().forEach((t) => t.stop());
          overlay.remove();
          payloadInput.value = payload;
          const parsed = parsePairingPayload(payload);
          if (parsed.deviceId) {
            // Auto-submit like the phone scanner flow does.
            const deviceIdInput = document.querySelector('[data-persist-key="pair-device-id"]');
            if (deviceIdInput) deviceIdInput.value = parsed.deviceId;
            pair(payload, parsed.deviceId, '');
          }
          return;
        }
      } catch { /* keep polling */ }
      requestAnimationFrame(() => setTimeout(tick, 250));
    };
    tick();
  } catch (error) {
    store.setMessage(error?.message ?? 'Camera unavailable; use manual entry');
  }
}

function deviceList(state) {
  if (state.loadingDeviceDetails && state.devices.length === 0) {
    return emptyPanel('Loading TVs', 'Reading paired TVs from Firebase...');
  }
  if (state.devices.length === 0) {
    return emptyPanel('No TVs paired', 'Pair the TV using the QR payload or manual code shown on the TV app.');
  }
  const online = state.devices.filter((d) => d.online).length;
  return h('div', { class: 'card' },
    h('div', { class: 'row' },
      h('div', { class: 'grow', style: { fontWeight: 700 } }, 'Paired Devices'),
      h('span', { class: 'small muted' }, `${online} Active`),
    ),
    state.devices.map((device) => deviceCard(state, device)),
  );
}

function deviceCard(state, device) {
  const selected = device.deviceId === state.selectedDeviceId;
  return h('div', { class: `card ${selected ? 'tint' : ''}`, style: selected ? { borderColor: 'var(--guard-navy)', borderWidth: '2px' } : {} },
    h('div', { class: 'row' },
      h('div', { class: 'icon-tile' }, '📺'),
      h('div', { class: 'grow' },
        h('div', { style: { fontWeight: 700 } }, device.label),
        h('div', { class: 'small muted ellipsis' }, device.deviceId),
      ),
      selected
        ? statusPill('Active', true)
        : h('button', { class: 'btn secondary small', onClick: () => selectDevice(device.deviceId) }, 'Select'),
      h('button', {
        class: 'btn danger-outline small',
        onClick: () => import('../main.js').then(({ confirmDialog }) =>
          confirmDialog(
            'Remove paired TV?',
            `This removes ${device.label} from the parent account and sends an unpair command to the TV.`,
            'Remove', true,
          ).then((yes) => { if (yes) removeDevice(device.deviceId, device.label); })),
      }, 'Remove'),
    ),
    h('div', { class: 'meta-grid' },
      metaTile('Mode', device.enforcementMode, device.enforcementMode !== 'unprotected'),
      metaTile('Health', device.protectionHealthy ? 'Healthy' : 'Needs setup', device.protectionHealthy),
    ),
    h('div', { class: 'meta-grid' },
      h('div', { class: 'meta-tile full' },
        h('span', { class: 'k' }, 'Last seen'),
        h('span', { style: { fontWeight: 700 } }, formatTimestamp(device.lastSeen)),
      ),
    ),
  );
}
