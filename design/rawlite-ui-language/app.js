const $ = (selector, scope = document) => scope.querySelector(selector);
const $$ = (selector, scope = document) => [...scope.querySelectorAll(selector)];

const seedClips = [
  {
    id: "pure-patio",
    title: "PURE10 · 17 AGO · 10:08",
    format: "PURE 10",
    formatLong: "HEVC MAIN10 HLG",
    resolution: "3840×2160",
    fps: 24,
    duration: 18.4,
    size: 192_800_000,
    sizeLabel: "183,9 MB",
    audio: "AAC · 48 KHZ",
    status: "FINALIZADA",
    scene: "patio",
    dropped: 0,
    storedFrames: 442,
    expectedFrames: 442,
    camera: "24 MM"
  },
  {
    id: "raw-interior",
    title: "RAW10 · 17 AGO · 09:42",
    format: "RAW10",
    formatLong: "BAYER RAW10C",
    resolution: "2048×1152",
    fps: 24,
    duration: 31.25,
    size: 2_640_000_000,
    sizeLabel: "2,46 GB",
    audio: "PCM 24 BIT · 48 KHZ",
    status: "ÍNTEGRA",
    scene: "interior",
    dropped: 0,
    storedFrames: 750,
    expectedFrames: 750,
    camera: "24 MM"
  },
  {
    id: "dng-sun",
    title: "CINEMADNG · 16 AGO · 14:12",
    format: "CinemaDNG",
    formatLong: "SECUENCIA DNG",
    resolution: "4080×3072",
    fps: 24,
    duration: 8.125,
    size: 5_940_000_000,
    sizeLabel: "5,53 GB",
    audio: "PCM 24 BIT · 48 KHZ",
    status: "195 DNG",
    scene: "dng",
    dropped: 0,
    storedFrames: 195,
    expectedFrames: 195,
    camera: "24 MM"
  },
  {
    id: "pure-night",
    title: "PURE10 · 15 AGO · 21:37",
    format: "PURE 10",
    formatLong: "HEVC MAIN10 HLG",
    resolution: "2048×1152",
    fps: 30,
    duration: 64.3,
    size: 438_000_000,
    sizeLabel: "417,7 MB",
    audio: "AAC · 48 KHZ",
    status: "FINALIZADA",
    scene: "night",
    dropped: 3,
    storedFrames: 1926,
    expectedFrames: 1929,
    camera: "24 MM"
  },
  {
    id: "raw-screen",
    title: "RAW10 · 14 AGO · 18:05",
    format: "RAW10",
    formatLong: "BAYER RAW10C",
    resolution: "2048×1152",
    fps: 24,
    duration: 13.0,
    size: 1_180_000_000,
    sizeLabel: "1,10 GB",
    audio: "PCM 24 BIT · 48 KHZ",
    status: "ÍNTEGRA",
    scene: "screen",
    dropped: 0,
    storedFrames: 312,
    expectedFrames: 312,
    camera: "24 MM"
  }
];

let clips = seedClips.map(clip => ({ ...clip }));
let galleryFormatFilter = "ALL";
let galleryFpsFilter = "ALL";
let gallerySortOrder = "newest";
let selected = new Set();
let selectionMode = false;
let activeClip = clips[0];
let playerSeconds = 0;
let playerTimer = null;
let playing = false;
let muted = false;
let rawPrepared = false;
let toastTimer = null;
let dragActive = false;
let dragDesiredState = true;
let longPressTimer = null;
let longPressOrigin = null;
let suppressNextClick = false;

const captureFormats = [
  {
    id: "hlg10",
    label: "HLG 10",
    short: "HEVC MAIN10",
    color: "REC.2020 / HLG",
    colorNote: "Metadatos Rec.2100",
    audio: "AAC · 48 KHZ",
    audioNote: "Integrado en MP4 (sin WAV duplicado)",
    bitrate: "AUTO · 82 MB/S",
    bitrateNote: "Límite medido del perfil",
    compatibleResolutions: ["uhd169", "2k43", "fhd169"],
    compatibility: "HEVC MAIN10 POR HARDWARE · 24/30 FPS · AUDIO INTEGRADO"
  },
  {
    id: "raw",
    label: "RAW",
    short: "BAYER SENSOR",
    color: "SENSOR / DNG",
    colorNote: "Perfil DNG Pixel 6",
    audio: "PCM · 48 KHZ",
    audioNote: "WAV sincronizado",
    bitrate: "VARIABLE",
    bitrateNote: "Secuencia DNG o Contenedor RAW10C",
    compatibleResolutions: ["full43", "2k43"],
    compatibility: "RAW SENSOR VERIFICADO · DNG O RAW10C · ESCRITURA DIRECTA"
  }
];

const captureResolutions = [
  { id: "full43", label: "4080×3072", name: "FULL 4K", ratio: "4:3", detail: "SECUENCIA CINEMADNG (12.5 MP)" },
  { id: "2k43", label: "2032×1536", name: "2K", ratio: "4:3", detail: "CONTENEDOR RAW10C (3.1 MP)" },
  { id: "uhd169", label: "3840×2160", name: "UHD", ratio: "16:9", detail: "8.3 MP · HEVC HLG" },
  { id: "fhd169", label: "1920×1080", name: "FHD", ratio: "16:9", detail: "2.1 MP · HEVC HLG" }
];

let appliedFormatId = "hlg10";
let appliedResolutionId = "uhd169";
let draftFormatId = appliedFormatId;
let draftResolutionId = appliedResolutionId;
let focusedProfileWheel = "format";

function icon(id) {
  return `<svg aria-hidden="true"><use href="#${id}"/></svg>`;
}

function formatDuration(seconds, precise = false) {
  const minutes = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  const millis = Math.floor((seconds % 1) * 1000);
  return `${String(minutes).padStart(2, "0")}:${String(secs).padStart(2, "0")}${precise ? `.${String(millis).padStart(3, "0")}` : ""}`;
}

function totalBytes(items) {
  return items.reduce((sum, clip) => sum + clip.size, 0);
}

function formatBytes(bytes) {
  if (bytes < 1_000_000_000) return `${(bytes / 1_000_000).toFixed(1).replace(".", ",")} MB`;
  return `${(bytes / 1_000_000_000).toFixed(2).replace(".", ",")} GB`;
}

function getFilteredClips() {
  let list = clips.filter(clip => {
    if (galleryFormatFilter === "HLG10" && clip.format !== "PURE 10") return false;
    if (galleryFormatFilter === "RAW10" && clip.format !== "RAW10") return false;
    if (galleryFormatFilter === "CINEMADNG" && clip.format !== "CinemaDNG") return false;
    if (galleryFpsFilter === "24" && clip.fps !== 24) return false;
    if (galleryFpsFilter === "30" && clip.fps !== 30) return false;
    return true;
  });
  if (gallerySortOrder === "oldest") {
    list = [...list].reverse();
  }
  return list;
}

function renderGallery() {
  const sheet = $("#contact-sheet");
  const filtered = getFilteredClips();
  sheet.innerHTML = filtered.map((clip, index) => `
    <article class="clip-card${selected.has(clip.id) ? " is-selected" : ""}" data-clip-id="${clip.id}" tabindex="0" aria-label="${clip.title}, ${clip.format}">
      <div class="clip-visual">
        <div class="scene scene--${clip.scene}"></div>
        <span class="format-flag">${clip.format}</span>
        <span class="clip-duration">${formatDuration(clip.duration)}</span>
        <span class="selection-mark">${icon("i-check")}</span>
      </div>
      <div class="clip-meta">
        <div class="clip-title-row"><strong class="clip-title">${clip.title}</strong><span class="clip-index">${String(index + 1).padStart(2, "0")}</span></div>
        <div class="clip-spec">${clip.resolution} · ${clip.fps} FPS · ${clip.camera} · ${clip.formatLong}</div>
        <div class="clip-status">
          <span class="confirmed">${clip.status}</span>
          <span>${clip.sizeLabel}</span>
          <span>${clip.audio}</span>
          <span class="${clip.dropped > 0 ? "warning" : ""}">${clip.dropped} PERDIDOS</span>
        </div>
      </div>
    </article>
  `).join("");
  updateGallerySummary(filtered);
  const statusEl = $("#gallery-filter-status");
  if (statusEl) {
    statusEl.textContent = galleryFormatFilter === "ALL" 
      ? "CONTACTO / TODAS LAS TOMAS" 
      : `CONTACTO / FILTRADO POR ${galleryFormatFilter}`;
  }
}

function updateGallerySummary(filtered = clips) {
  const summary = $("#gallery-summary");
  const label = filtered.length === 1 ? "TOMA" : "TOMAS";
  summary.textContent = `${filtered.length} ${label} · ${formatBytes(totalBytes(filtered))}`;
  if (!filtered.length) {
    $("#contact-sheet").innerHTML = `<div class="empty-state"><strong>SIN RESULTADOS</strong><span>No hay tomas que coincidan con los filtros aplicados.</span></div>`;
  }
}
function updateGallerySummary() {
  const summary = $("#gallery-summary");
  const label = clips.length === 1 ? "TOMA" : "TOMAS";
  summary.textContent = `${clips.length} ${label} · ${formatBytes(totalBytes(clips))}`;
  if (!clips.length) {
    $("#contact-sheet").innerHTML = `<div class="empty-state"><strong>NO HAY TOMAS</strong><span>Las nuevas grabaciones aparecerán aquí.</span></div>`;
  }
}

function setSelectionMode(enabled) {
  selectionMode = enabled;
  if (!enabled) selected.clear();
  $("#screen-gallery").classList.toggle("selection-mode", enabled);
  $("#selection-toolbar").classList.toggle("is-active", enabled);
  $("#selection-toolbar").setAttribute("aria-hidden", String(!enabled));
  updateSelectionUi();
}

function setClipSelected(id, value) {
  if (value) selected.add(id); else selected.delete(id);
  const card = $(`.clip-card[data-clip-id="${id}"]`);
  card?.classList.toggle("is-selected", value);
  updateSelectionUi();
}

function toggleSelected(id) {
  setClipSelected(id, !selected.has(id));
}

function updateSelectionUi() {
  const chosen = clips.filter(clip => selected.has(clip.id));
  $("#selection-label").textContent = `${chosen.length} ${chosen.length === 1 ? "SELECCIONADA" : "SELECCIONADAS"}`;
  $("#selection-size").textContent = formatBytes(totalBytes(chosen));
  $$("#selection-toolbar .action-button").forEach(button => button.disabled = chosen.length === 0);
  $$(".clip-card").forEach(card => card.classList.toggle("is-selected", selected.has(card.dataset.clipId)));
}

function showScreen(name) {
  closePopover();
  $$(".screen").forEach(screen => screen.classList.toggle("is-visible", screen.dataset.screen === name));
  $$(".lab-tab").forEach(tab => tab.classList.toggle("is-active", tab.dataset.screen === name));
  if (name !== "player") stopPlayback();
}

function openPlayer(clip) {
  activeClip = clip;
  playerSeconds = 0;
  rawPrepared = clip.format === "PURE 10";
  stopPlayback();
  $("#player-title").textContent = clip.title;
  $("#player-subtitle").textContent = `${clip.formatLong} · ${clip.resolution} · ${clip.fps} FPS`;
  $("#transport-format").textContent = clip.format;
  $("#transport-fps").textContent = `${clip.fps} FPS`;
  $("#transport-audio").textContent = clip.audio;
  $("#total-time").textContent = formatDuration(clip.duration, true);
  $("#loss-count").textContent = String(clip.dropped);
  $("#frame-loss").dataset.loss = String(clip.dropped > 0);
  $("#media-picture").className = `media-picture scene scene--${clip.scene}`;
  $("#convert-current").classList.toggle("is-disabled", clip.format !== "RAW10");
  $("#frame-readout").textContent = clip.format === "PURE 10" ? "10 BIT" : `FOT. 1/${clip.expectedFrames}`;
  $("#player-status-text").textContent = clip.format === "PURE 10" ? "LISTO" : "LISTO PARA REVELAR";
  $("#timeline").value = "0";
  updateTimelineStyle();
  updatePlayerReadout();
  showScreen("player");
}

function updatePlayerReadout() {
  const clip = activeClip;
  $("#current-time").textContent = formatDuration(playerSeconds, true);
  if (clip.format !== "PURE 10") {
    const frame = Math.min(clip.expectedFrames, Math.floor(playerSeconds * clip.fps) + 1);
    $("#frame-readout").textContent = `FOT. ${frame}/${clip.expectedFrames}`;
  }
  const progress = clip.duration ? (playerSeconds / clip.duration) * 1000 : 0;
  $("#timeline").value = String(Math.min(1000, progress));
  updateTimelineStyle();
}

function updateTimelineStyle() {
  const range = $("#timeline");
  range.style.setProperty("--progress", `${Number(range.value) / 10}%`);
}

function setPlayButton(isPlaying) {
  $("#play-toggle").innerHTML = icon(isPlaying ? "i-pause" : "i-play");
  $("#play-toggle").setAttribute("aria-label", isPlaying ? "Pausar" : "Reproducir");
  $("#media-stage").classList.toggle("is-playing", isPlaying);
}

function startPlayback() {
  if (playing) return;
  if (activeClip.format !== "PURE 10" && !rawPrepared) {
    revealRawFrames();
    return;
  }
  if (playerSeconds >= activeClip.duration) playerSeconds = 0;
  playing = true;
  setPlayButton(true);
  $("#player-status-text").textContent = "REPRODUCIENDO";
  let previous = performance.now();
  playerTimer = window.setInterval(() => {
    const now = performance.now();
    playerSeconds += (now - previous) / 1000;
    previous = now;
    if (playerSeconds >= activeClip.duration) {
      playerSeconds = activeClip.duration;
      stopPlayback();
      $("#player-status-text").textContent = "FIN DE TOMA";
    }
    updatePlayerReadout();
  }, 80);
}

function stopPlayback() {
  playing = false;
  if (playerTimer) window.clearInterval(playerTimer);
  playerTimer = null;
  setPlayButton(false);
}

function revealRawFrames() {
  rawPrepared = true;
  let loaded = 0;
  $("#player-status-text").textContent = "REVELANDO 0/8";
  const revealTimer = window.setInterval(() => {
    loaded += 1;
    $("#player-status-text").textContent = `REVELANDO ${loaded}/8`;
    if (loaded === 8) {
      window.clearInterval(revealTimer);
      $("#player-status-text").textContent = "BÚFER LISTO";
      window.setTimeout(startPlayback, 180);
    }
  }, 90);
}

function stepFrame(direction) {
  stopPlayback();
  playerSeconds = Math.max(0, Math.min(activeClip.duration, playerSeconds + direction / activeClip.fps));
  updatePlayerReadout();
  $("#player-status-text").textContent = direction > 0 ? "+1 FOTOGRAMA" : "−1 FOTOGRAMA";
}

function showPopover(anchor, html) {
  const popover = $("#popover");
  popover.innerHTML = html;
  popover.hidden = false;
  const rect = anchor.getBoundingClientRect();
  const width = Math.min(310, window.innerWidth - 24);
  const left = Math.max(12, Math.min(window.innerWidth - width - 12, rect.right - width));
  const top = Math.min(window.innerHeight - popover.offsetHeight - 12, rect.bottom + 6);
  popover.style.width = `${width}px`;
  popover.style.left = `${left}px`;
  popover.style.top = `${Math.max(12, top)}px`;
}

function closePopover() {
  $("#popover").hidden = true;
}

function showToolsMenu(anchor, items) {
  const rawItems = items.filter(clip => clip.format === "RAW10");
  showPopover(anchor, `
    <span class="popover-section-label">ACCIONES GENERALES</span>
    <button class="popover-button" data-menu-action="share">${icon("i-share")}COMPARTIR ${items.length > 1 ? `${items.length} TOMAS` : "TOMA"}</button>
    <button class="popover-button" data-menu-action="info">${icon("i-info")}INFORMACIÓN</button>
    <div class="popover-divider"></div>
    <span class="popover-section-label">HERRAMIENTAS DE FORMATO</span>
    <button class="popover-button" data-menu-action="convert" ${rawItems.length ? "" : "disabled"}>${icon("i-tools")}CONVERTIR ${rawItems.length || "—"} RAW10 A CINEMADNG</button>
    <button class="popover-button" data-menu-action="integrity">${icon("i-check")}VERIFICAR INTEGRIDAD</button>
    <div class="popover-divider"></div>
    <button class="popover-button destructive" data-menu-action="delete">${icon("i-trash")}BORRAR</button>
  `);
  $("#popover").onclick = event => {
    const button = event.target.closest("[data-menu-action]");
    if (!button || button.disabled) return;
    closePopover();
    const action = button.dataset.menuAction;
    if (action === "share") showToast(`LISTO PARA COMPARTIR · ${items.length} ${items.length === 1 ? "TOMA" : "TOMAS"}`);
    if (action === "info") showInfoDialog(items);
    if (action === "convert") showConversionDialog(rawItems);
    if (action === "integrity") showIntegrityDialog(items);
    if (action === "delete") showDeleteDialog(items);
  };
}

function showDialog({ kicker = "RAWLITE", title, body, actions }) {
  $("#dialog-kicker").textContent = kicker;
  $("#dialog-title").textContent = title;
  $("#dialog-body").innerHTML = body;
  const actionWrap = $("#dialog-actions");
  actionWrap.innerHTML = "";
  actions.forEach(action => {
    const button = document.createElement("button");
    button.className = `dialog-button${action.kind ? ` ${action.kind}` : ""}`;
    button.textContent = action.label;
    button.onclick = action.onClick;
    actionWrap.appendChild(button);
  });
  $("#dialog-backdrop").hidden = false;
}

function closeDialog() {
  $("#dialog-backdrop").hidden = true;
}

function showConversionDialog(rawItems) {
  if (!rawItems.length) {
    showDialog({
      kicker: "HERRAMIENTA DE FORMATO",
      title: "SIN RAW10",
      body: `<p>PURE 10 ya es vídeo MP4 y CinemaDNG ya es una secuencia DNG. Ninguno requiere esta conversión.</p>`,
      actions: [{ label: "ENTENDIDO", kind: "primary", onClick: closeDialog }]
    });
    return;
  }
  const source = totalBytes(rawItems);
  const estimate = Math.round(source * 2.35);
  showDialog({
    kicker: "HERRAMIENTA DE FORMATO",
    title: "CONVERTIR RAW10",
    body: `
      <p>Se creará una toma CinemaDNG nueva por cada RAW10 compatible. El material original permanece intacto.</p>
      <div class="dialog-metrics">
        <div><span>RAW10 SELECCIONADO</span><strong>${formatBytes(source)}</strong></div>
        <div><span>CINEMADNG ESTIMADO</span><strong>+${formatBytes(estimate)}</strong></div>
        <div><span>TOMAS COMPATIBLES</span><strong>${rawItems.length}</strong></div>
        <div><span>ESPACIO DISPONIBLE</span><strong>42,8 GB</strong></div>
      </div>
      <p class="dialog-note">La conversión puede continuar con la pantalla apagada. Grabar queda bloqueado mientras la herramienta utiliza almacenamiento intensivamente.</p>
    `,
    actions: [
      { label: "CANCELAR", onClick: closeDialog },
      { label: "CONVERTIR", kind: "primary", onClick: () => runConversion(rawItems) }
    ]
  });
}

function runConversion(rawItems) {
  const body = $("#dialog-body");
  $("#dialog-title").textContent = "CONVIRTIENDO RAW10";
  $("#dialog-actions").innerHTML = "";
  body.innerHTML = `<p>Procesando ${rawItems[0].title}.</p><div class="progress-track"><span></span></div><div class="progress-copy"><span id="progress-frames">0 / ${rawItems.reduce((n, clip) => n + clip.storedFrames, 0)} FOTOGRAMAS</span><span id="progress-percent">0 %</span></div>`;
  let progress = 0;
  const totalFrames = rawItems.reduce((n, clip) => n + clip.storedFrames, 0);
  const timer = window.setInterval(() => {
    progress = Math.min(100, progress + 4);
    $(".progress-track span", body).style.setProperty("--progress", `${progress}%`);
    $("#progress-percent").textContent = `${progress} %`;
    $("#progress-frames").textContent = `${Math.round(totalFrames * progress / 100)} / ${totalFrames} FOTOGRAMAS`;
    if (progress === 100) {
      window.clearInterval(timer);
      $("#dialog-title").textContent = "CONVERSIÓN COMPLETA";
      body.innerHTML = `<p><strong>${rawItems.length} ${rawItems.length === 1 ? "toma convertida" : "tomas convertidas"}.</strong> Los RAW10 originales permanecen intactos.</p><p class="dialog-note">Las nuevas secuencias aparecerían ahora como tomas CinemaDNG independientes.</p>`;
      const done = document.createElement("button");
      done.className = "dialog-button primary";
      done.textContent = "ENTENDIDO";
      done.onclick = () => {
        closeDialog();
        setSelectionMode(false);
        showToast("CONVERSIÓN COMPLETA · ORIGINALES CONSERVADOS");
      };
      $("#dialog-actions").appendChild(done);
    }
  }, 70);
}

function showDeleteDialog(items) {
  showDialog({
    kicker: "ACCIÓN DESTRUCTIVA",
    title: `BORRAR ${items.length === 1 ? "TOMA" : "TOMAS"}`,
    body: `<p>Se eliminarán permanentemente <strong>${items.length} ${items.length === 1 ? "toma" : "tomas"}</strong>, ${formatBytes(totalBytes(items))}.</p><p class="dialog-note is-danger">Esta acción no modifica ninguna toma que no esté seleccionada.</p>`,
    actions: [
      { label: "CANCELAR", onClick: closeDialog },
      { label: "BORRAR", kind: "danger", onClick: () => {
        const ids = new Set(items.map(item => item.id));
        clips = clips.filter(clip => !ids.has(clip.id));
        closeDialog();
        setSelectionMode(false);
        renderGallery();
        showScreen("gallery");
        showToast(`${items.length} ${items.length === 1 ? "TOMA BORRADA" : "TOMAS BORRADAS"}`);
      }}
    ]
  });
}

function showIntegrityDialog(items) {
  const lost = items.reduce((sum, clip) => sum + clip.dropped, 0);
  const frames = items.reduce((sum, clip) => sum + clip.storedFrames, 0);
  const expected = items.reduce((sum, clip) => sum + clip.expectedFrames, 0);
  showDialog({
    kicker: "DIAGNÓSTICO",
    title: "INTEGRIDAD DE TOMA",
    body: `<div class="dialog-metrics"><div><span>GUARDADOS</span><strong>${frames}</strong></div><div><span>TIMELINE</span><strong>${expected}</strong></div><div><span>PERDIDOS</span><strong class="${lost ? "is-red" : "is-green"}">${lost}</strong></div><div><span>AUDIO</span><strong>${items.every(item => item.audio) ? "PRESENTE" : "REVISAR"}</strong></div></div><p class="dialog-note">El contador diferencia pérdidas reales de los saltos que el visor puede hacer para mantener el reloj.</p>`,
    actions: [{ label: "ENTENDIDO", kind: "primary", onClick: closeDialog }]
  });
}

function showInfoDialog(items) {
  const formats = [...new Set(items.map(item => item.format))].join(" · ");
  showDialog({
    kicker: "INFORMACIÓN",
    title: items.length === 1 ? items[0].title : `${items.length} TOMAS`,
    body: `<div class="dialog-metrics"><div><span>FORMATOS</span><strong>${formats}</strong></div><div><span>TAMAÑO</span><strong>${formatBytes(totalBytes(items))}</strong></div><div><span>DURACIÓN TOTAL</span><strong>${formatDuration(items.reduce((sum, item) => sum + item.duration, 0), true)}</strong></div><div><span>PERDIDOS</span><strong>${items.reduce((sum, item) => sum + item.dropped, 0)}</strong></div></div>`,
    actions: [{ label: "CERRAR", kind: "primary", onClick: closeDialog }]
  });
}

function showToast(message) {
  const toast = $("#toast");
  toast.textContent = message;
  toast.classList.add("is-visible");
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toast.classList.remove("is-visible"), 2300);
}

function resetDemo() {
  clips = seedClips.map(clip => ({ ...clip }));
  selected.clear();
  setSelectionMode(false);
  activeClip = clips[0];
  playerSeconds = 0;
  rawPrepared = false;
  stopPlayback();
  renderGallery();
  openPlayer(activeClip);
  showScreen("gallery");
  showToast("DEMO REINICIADA");
}

function wireGalleryGestures() {
  const sheet = $("#contact-sheet");
  sheet.addEventListener("click", event => {
    const card = event.target.closest(".clip-card");
    if (!card) return;
    if (suppressNextClick) {
      suppressNextClick = false;
      return;
    }
    if (selectionMode) toggleSelected(card.dataset.clipId);
    else openPlayer(clips.find(clip => clip.id === card.dataset.clipId));
  });
  sheet.addEventListener("keydown", event => {
    if (event.key !== "Enter" && event.key !== " ") return;
    const card = event.target.closest(".clip-card");
    if (!card) return;
    event.preventDefault();
    if (selectionMode) toggleSelected(card.dataset.clipId);
    else openPlayer(clips.find(clip => clip.id === card.dataset.clipId));
  });
  sheet.addEventListener("pointerdown", event => {
    const card = event.target.closest(".clip-card");
    if (!card) return;
    longPressOrigin = { x: event.clientX, y: event.clientY, id: card.dataset.clipId };
    if (selectionMode) {
      dragActive = true;
      dragDesiredState = !selected.has(card.dataset.clipId);
      setClipSelected(card.dataset.clipId, dragDesiredState);
      suppressNextClick = true;
      if (event.pointerType === "mouse") event.preventDefault();
      return;
    }
    longPressTimer = window.setTimeout(() => {
      setSelectionMode(true);
      setClipSelected(card.dataset.clipId, true);
      suppressNextClick = true;
      navigator.vibrate?.(18);
    }, 430);
  });
  sheet.addEventListener("pointerover", event => {
    if (!dragActive) return;
    const card = event.target.closest(".clip-card");
    if (card) setClipSelected(card.dataset.clipId, dragDesiredState);
  });
  window.addEventListener("pointermove", event => {
    if (longPressTimer && longPressOrigin) {
      if (Math.hypot(event.clientX - longPressOrigin.x, event.clientY - longPressOrigin.y) > 9) {
        window.clearTimeout(longPressTimer);
        longPressTimer = null;
      }
    }
    if (!dragActive) return;
    const card = document.elementFromPoint(event.clientX, event.clientY)?.closest(".clip-card");
    if (card) setClipSelected(card.dataset.clipId, dragDesiredState);
    const scroller = $("#gallery-scroll");
    const bounds = scroller.getBoundingClientRect();
    if (event.clientY < bounds.top + 54) scroller.scrollBy({ top: -18 });
    if (event.clientY > bounds.bottom - 54) scroller.scrollBy({ top: 18 });
  });
  window.addEventListener("pointerup", () => {
    if (longPressTimer) window.clearTimeout(longPressTimer);
    longPressTimer = null;
    longPressOrigin = null;
    dragActive = false;
    window.setTimeout(() => { suppressNextClick = false; }, 40);
  });
  window.addEventListener("pointercancel", () => {
    if (longPressTimer) window.clearTimeout(longPressTimer);
    longPressTimer = null;
    longPressOrigin = null;
    dragActive = false;
  });
}

function formatById(id) {
  return captureFormats.find(format => format.id === id) || captureFormats[0];
}

function resolutionById(id) {
  return captureResolutions.find(resolution => resolution.id === id) || captureResolutions[0];
}

function renderCaptureProfileEditor() {
  const format = formatById(draftFormatId);
  if (!format.compatibleResolutions.includes(draftResolutionId)) {
    draftResolutionId = format.compatibleResolutions[0];
  }
  const resolution = resolutionById(draftResolutionId);

  $("#format-wheel-list").innerHTML = captureFormats.map(item => `
    <button class="wheel-option${item.id === draftFormatId ? " is-selected" : ""}" data-format-id="${item.id}">
      <span>${item.label}</span><small>${item.short}</small>
    </button>
  `).join("");

  $("#resolution-wheel-list").innerHTML = captureResolutions.map(item => {
    const compatible = format.compatibleResolutions.includes(item.id);
    return `
      <button class="wheel-option${item.id === draftResolutionId ? " is-selected" : ""}${compatible ? "" : " is-locked"}"
        data-resolution-id="${item.id}" ${compatible ? "" : "disabled"} aria-disabled="${String(!compatible)}">
        <span>${item.name} <i>${item.label}</i></span>
        <small>${compatible ? `${item.ratio} · ${item.detail}` : `NO DISPONIBLE EN ${format.label}`}</small>
      </button>
    `;
  }).join("");

  $$(".config-wheel").forEach(wheel => wheel.classList.toggle("is-focused", wheel.dataset.wheel === focusedProfileWheel));
  $("#profile-selection-value").textContent = `${format.label} · ${resolution.label}`;
  $("#profile-selection-detail").textContent = `${resolution.ratio} · ${format.color}`;
  $("#compatibility-copy").textContent = format.compatibility;

  $$("[data-format-id]").forEach(button => button.onclick = () => {
    draftFormatId = button.dataset.formatId;
    focusedProfileWheel = "format";
    renderCaptureProfileEditor();
  });
  $$("[data-resolution-id]").forEach(button => button.onclick = () => {
    if (button.disabled) return;
    draftResolutionId = button.dataset.resolutionId;
    focusedProfileWheel = "resolution";
    renderCaptureProfileEditor();
  });
}

function openCaptureProfileEditor() {
  draftFormatId = appliedFormatId;
  draftResolutionId = appliedResolutionId;
  focusedProfileWheel = "format";
  renderCaptureProfileEditor();
  const editor = $("#capture-profile-editor");
  editor.classList.add("is-open");
  editor.setAttribute("aria-hidden", "false");
  $("#format-wheel").focus({ preventScroll: true });
}

function closeCaptureProfileEditor() {
  const editor = $("#capture-profile-editor");
  editor.classList.remove("is-open");
  editor.setAttribute("aria-hidden", "true");
  $("#open-capture-profile").focus({ preventScroll: true });
}

function applyCaptureProfile() {
  appliedFormatId = draftFormatId;
  appliedResolutionId = draftResolutionId;
  const format = formatById(appliedFormatId);
  const resolution = resolutionById(appliedResolutionId);
  $("#project-format-value").textContent = format.label;
  $("#project-resolution-value").textContent = resolution.label;
  $("#project-profile-meta").textContent = `${format.short} · ${format.color} · ${resolution.ratio}`;
  $("#project-bitrate").textContent = format.bitrate;
  $("#project-bitrate-note").textContent = format.bitrateNote;
  $("#project-audio").textContent = format.audio;
  $("#project-audio-note").textContent = format.audioNote;
  $("#project-color").textContent = format.color;
  $("#project-color-note").textContent = format.colorNote;
  closeCaptureProfileEditor();
  showToast(`PROYECTO · ${format.label} · ${resolution.label}`);
}

function moveProfileSelection(direction) {
  if (focusedProfileWheel === "format") {
    const currentIndex = captureFormats.findIndex(item => item.id === draftFormatId);
    const nextIndex = Math.max(0, Math.min(captureFormats.length - 1, currentIndex + direction));
    draftFormatId = captureFormats[nextIndex].id;
  } else {
    const valid = captureResolutions.filter(item => formatById(draftFormatId).compatibleResolutions.includes(item.id));
    const currentIndex = valid.findIndex(item => item.id === draftResolutionId);
    const nextIndex = Math.max(0, Math.min(valid.length - 1, currentIndex + direction));
    draftResolutionId = valid[nextIndex].id;
  }
  navigator.vibrate?.(8);
  renderCaptureProfileEditor();
}

function focusProfileWheel(name) {
  focusedProfileWheel = name;
  renderCaptureProfileEditor();
  $(`#${name}-wheel`).focus({ preventScroll: true });
}

function profileEditorIsOpen() {
  return $("#capture-profile-editor").classList.contains("is-open");
}

function init() {
  renderGallery();
  wireGalleryGestures();

  $$(".lab-tab").forEach(tab => tab.addEventListener("click", () => {
    if (tab.dataset.screen === "player") openPlayer(activeClip || clips[0]);
    else showScreen(tab.dataset.screen);
  }));
  $("#select-mode").onclick = () => setSelectionMode(true);
  $("#selection-cancel").onclick = () => setSelectionMode(false);
  $("#share-selected").onclick = () => showToast(`LISTO PARA COMPARTIR · ${selected.size} ${selected.size === 1 ? "TOMA" : "TOMAS"}`);
  $("#tools-selected").onclick = event => showToolsMenu(event.currentTarget, clips.filter(clip => selected.has(clip.id)));
  $("#delete-selected").onclick = () => showDeleteDialog(clips.filter(clip => selected.has(clip.id)));
  let activeFormatFilter = "ALL";
  let activeSortOrder = "newest";

  window.applyGalleryFilter = (format) => {
    activeFormatFilter = format;
    const cards = $$(".clip-card");
    let visibleCount = 0;
    cards.forEach(card => {
      const clip = clips.find(c => c.id === card.dataset.clipId);
      if (!clip) return;
      const match = format === "ALL" ||
        (format === "HLG10" && clip.format === "PURE 10") ||
        (format === "RAW10" && clip.format === "RAW10") ||
        (format === "CINEMADNG" && clip.format === "CinemaDNG");
      card.style.display = match ? "" : "none";
      if (match) visibleCount++;
    });
    const statusLabel = format === "ALL" ? "TODAS LAS TOMAS" : (format === "HLG10" ? "SÓLO HLG 10" : (format === "RAW10" ? "SÓLO RAW 10" : "SÓLO CINEMADNG"));
    const filterEl = $("#gallery-filter-status");
    if (filterEl) filterEl.textContent = `FILTRO / ${statusLabel}`;
    showToast(`FILTRO · ${statusLabel} (${visibleCount})`);
  };

  $("#gallery-more").onclick = event => {
    showPopover(event.currentTarget, `
      <span class="popover-section-label">FILTRAR POR FORMATO</span>
      <button class="popover-button" data-filter-format="ALL">${icon("i-check")}TODOS LOS FORMATOS</button>
      <button class="popover-button" data-filter-format="HLG10">HLG 10 (HEVC MAIN10)</button>
      <button class="popover-button" data-filter-format="RAW10">RAW 10 (2K RAW10C)</button>
      <button class="popover-button" data-filter-format="CINEMADNG">CINEMADNG (4K DNG)</button>
      <div class="popover-divider"></div>
      <span class="popover-section-label">ORDENAR TOMAS</span>
      <button class="popover-button" data-sort-order="newest">${icon("i-check")}MÁS RECIENTES PRIMERO</button>
      <button class="popover-button" data-sort-order="oldest">MÁS ANTIGUAS PRIMERO</button>
      <div class="popover-divider"></div>
      <button class="popover-button" data-gallery-action="select-all">${icon("i-tools")}SELECCIONAR TODO</button>
      <button class="popover-button" data-gallery-action="location">${icon("i-info")}UBICACIÓN DE TOMAS</button>
    `);
    $("#popover").onclick = popEvent => {
      const target = popEvent.target.closest("[data-filter-format], [data-sort-order], [data-gallery-action]");
      if (!target) return;
      closePopover();
      if (target.dataset.filterFormat) {
        galleryFormatFilter = target.dataset.filterFormat;
        renderGallery();
        showToast(`FILTRO · ${galleryFormatFilter}`);
      } else if (target.dataset.sortOrder) {
        gallerySortOrder = target.dataset.sortOrder;
        renderGallery();
        showToast(`ORDEN · ${gallerySortOrder === "newest" ? "RECIENTES" : "ANTIGUAS"}`);
      } else if (target.dataset.galleryAction === "select-all") {
        setSelectionMode(true);
        getFilteredClips().forEach(clip => setClipSelected(clip.id, true));
      } else if (target.dataset.galleryAction === "location") {
        showToast("ANDROID / MOVIES / RAWLITE");
      }
    };
  };
  $("#player-back")  $("#player-back").onclick = () => showScreen("gallery");
  $("#play-toggle").onclick = () => playing ? stopPlayback() : startPlayback();
  $("#previous-frame").onclick = () => stepFrame(-1);
  $("#next-frame").onclick = () => stepFrame(1);
  $("#mute-toggle").onclick = () => {
    muted = !muted;
    $("#mute-toggle").innerHTML = icon(muted ? "i-muted" : "i-volume");
    showToast(muted ? "AUDIO SILENCIADO" : "AUDIO ACTIVO");
  };
  $("#timeline").addEventListener("input", event => {
    stopPlayback();
    playerSeconds = activeClip.duration * Number(event.target.value) / 1000;
    updatePlayerReadout();
    $("#player-status-text").textContent = "BUSCANDO";
  });
  $("#convert-current").onclick = () => {
    if (activeClip.format === "RAW10") showConversionDialog([activeClip]);
  };
  $("#integrity-current").onclick = () => showIntegrityDialog([activeClip]);
  $("#delete-current").onclick = () => showDeleteDialog([activeClip]);
  $("#player-more").onclick = event => showToolsMenu(event.currentTarget, [activeClip]);
  $("#reset-demo").onclick = resetDemo;
  $$('[data-action="camera"]').forEach(button => button.onclick = () => showToast("VOLVERÍA A LA CÁMARA"));

  $$(".settings-tab").forEach(button => button.onclick = () => {
    $$(".settings-tab").forEach(item => item.classList.toggle("is-active", item === button));
    $$(".settings-group").forEach(group => group.classList.toggle("is-active", group.dataset.settingsGroup === button.dataset.settingsGroup));
  });
  $$(".grid-choice").forEach(choice => choice.onclick = () => {
    $$(".grid-choice").forEach(item => {
      item.classList.toggle("is-active", item === choice);
      item.setAttribute("aria-checked", String(item === choice));
    });
    $("#grid-preview").dataset.grid = choice.dataset.gridChoice;
    showToast(`CUADRÍCULA · ${$("strong", choice).textContent}`);
  });
  $$(".segmented-control button").forEach(button => button.onclick = () => {
    const control = button.closest(".segmented-control");
    $$("button", control).forEach(item => item.classList.toggle("is-active", item === button));
    showToast(`${control.dataset.segmented === "handedness" ? "CONTROL" : "HÁPTICA"} · ${button.textContent}`);
  });
    // Wire composition grids
  $$("[data-grid-choice]").forEach(button => {
    button.addEventListener("click", () => {
      const choice = button.dataset.gridChoice;
      $$("[data-grid-choice]").forEach(b => {
        b.classList.toggle("is-active", b === button);
        b.setAttribute("aria-checked", String(b === button));
      });
      const preview = $("#grid-preview");
      preview.dataset.grid = choice;
      const invertBtn = $("#toggle-grid-invert");
      if (invertBtn) {
        invertBtn.disabled = !(choice === "golden" || choice === "diagonal");
        invertBtn.style.opacity = (choice === "golden" || choice === "diagonal") ? "1" : "0.35";
      }
      showToast(`CUADRÍCULA · ${button.querySelector("strong").textContent}`);
    });
  });

  // Botón Invertir para Áurea y Diagonal
  const invertBtn = $("#toggle-grid-invert");
  if (invertBtn) {
    invertBtn.onclick = () => {
      const preview = $("#grid-preview");
      const current = preview.dataset.gridInverted === "true";
      preview.dataset.gridInverted = String(!current);
      invertBtn.classList.toggle("is-active", !current);
      invertBtn.setAttribute("aria-pressed", String(!current));
      showToast(!current ? "ORIENTACIÓN · INVERTIDA (⇄)" : "ORIENTACIÓN · NORMAL");
    };
  }

  // Zonas Seguras Superpuestas y Sumables (DaVinci Resolve Style)
  $$(".safe-toggle-cb").forEach(checkbox => {
    checkbox.addEventListener("change", () => {
      const zoneId = checkbox.dataset.safeZone;
      const targetLayer = $(`#safe-${zoneId}`);
      if (targetLayer) {
        targetLayer.classList.toggle("is-active", checkbox.checked);
      }
      showToast(checkbox.checked ? `ZONA SEGURA SUMADA · ${zoneId.toUpperCase()}` : `ZONA SEGURA APAGADA · ${zoneId.toUpperCase()}`);
    });
  });

  // Wire Invert button for Golden and Diagonal
  const invertBtn = $("#btn-invert-grid");
  if (invertBtn) {
    invertBtn.addEventListener("click", () => {
      const preview = $("#grid-preview");
      const current = preview.dataset.gridInverted === "true";
      const next = !current;
      preview.dataset.gridInverted = String(next);
      invertBtn.classList.toggle("is-active", next);
      invertBtn.setAttribute("aria-pressed", String(next));
      showToast(next ? "CUADRÍCULA · INVERTIDA ⇄" : "CUADRÍCULA · ESTÁNDAR");
    });
  }

  // Wire Additive Safe Zones (DaVinci Resolve Style)
  $$(".safe-zone-toggle").forEach(cb => {
    cb.addEventListener("change", () => {
      const safeId = cb.dataset.safeId;
      const overlay = $(`#overlay-${safeId}`);
      if (overlay) {
        overlay.classList.toggle("is-active", cb.checked);
      }
      const names = {
        reels916: "ÁREA SEGURA · 9:16 REELS/TIKTOK",
        feed11: "ÁREA SEGURA · 1:1 FEED",
        portrait45: "ÁREA SEGURA · 4:5 RETRATO",
        action90: "ÁREA SEGURA · 90% ACTION",
        title80: "ÁREA SEGURA · 80% TITLE"
      };
      showToast(`${names[safeId] || "ZONA SEGURA"} · ${cb.checked ? "ACTIVA" : "DESACTIVADA"}`);
    });
  });

  $$(".switch-input").forEach(input => input.addEventListener("change", () => showToast(input.checked ? "AJUSTE ACTIVADO" : "AJUSTE DESACTIVADO")));
  $$(".settings-action").forEach(button => button.onclick = () => showToast(button.textContent.trim()));
  $$(".setting-row-button").forEach(button => button.onclick = () => showToast($("strong", button).textContent));
  $("#open-capture-profile").onclick = openCaptureProfileEditor;
  $("#close-capture-profile").onclick = closeCaptureProfileEditor;
  $("#cancel-capture-profile").onclick = closeCaptureProfileEditor;
  $("#apply-capture-profile").onclick = applyCaptureProfile;
  $$(".config-wheel").forEach(wheel => {
    wheel.onclick = event => {
      if (event.target.closest(".wheel-option")) return;
      focusProfileWheel(wheel.dataset.wheel);
    };
    wheel.onfocus = () => {
      focusedProfileWheel = wheel.dataset.wheel;
      $$(".config-wheel").forEach(item => item.classList.toggle("is-focused", item === wheel));
    };
  });

  document.addEventListener("pointerdown", event => {
    if (!$("#popover").hidden && !event.target.closest("#popover") && !event.target.closest("#gallery-more, #player-more, #tools-selected")) closePopover();
  });
  $("#dialog-backdrop").addEventListener("pointerdown", event => {
    if (event.target === event.currentTarget) closeDialog();
  });
  document.addEventListener("keydown", event => {
    if (profileEditorIsOpen()) {
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault();
        focusProfileWheel(event.key === "ArrowLeft" ? "format" : "resolution");
      } else if (event.key === "ArrowUp" || event.key === "ArrowDown") {
        event.preventDefault();
        moveProfileSelection(event.key === "ArrowUp" ? -1 : 1);
      } else if (event.key === "Enter") {
        event.preventDefault();
        applyCaptureProfile();
      } else if (event.key === "Escape") {
        event.preventDefault();
        closeCaptureProfileEditor();
      }
      return;
    }
    if (event.key === "Escape") {
      if (!$("#dialog-backdrop").hidden) closeDialog();
      else if (!$("#popover").hidden) closePopover();
      else if (selectionMode) setSelectionMode(false);
      else showScreen("gallery");
    }
    if (["1", "2", "3", "4"].includes(event.key) && !event.metaKey && !event.ctrlKey) {
      const names = ["gallery", "settings", "language"];
      const target = names[Number(event.key) - 1];
      if (target === "player") openPlayer(activeClip || clips[0]); else showScreen(target);
    }
  });

  openPlayer(activeClip);
  showScreen("gallery");
}

init();
