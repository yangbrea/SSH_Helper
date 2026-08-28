import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { SearchAddon } from '@xterm/addon-search';
import { WebLinksAddon } from '@xterm/addon-web-links';

const terminal = new Terminal({
  cursorBlink: true,
  cursorStyle: 'bar',
  allowTransparency: false,
  convertEol: false,
  disableStdin: false,
  fontFamily: 'Roboto Mono, Noto Sans Mono CJK SC, monospace',
  fontSize: 14,
  lineHeight: 1.15,
  scrollback: 10000,
  theme: {
    background: '#000000',
    foreground: '#dff8fb',
    cursor: '#22d3ee',
    cursorAccent: '#000000',
    selectionBackground: '#155e7599',
    black: '#07131f',
    red: '#ff7b7b',
    green: '#2dd4bf',
    yellow: '#f5d76e',
    blue: '#60a5fa',
    magenta: '#c084fc',
    cyan: '#22d3ee',
    white: '#dff8fb',
    brightBlack: '#547080',
    brightRed: '#ff9a9a',
    brightGreen: '#5eead4',
    brightYellow: '#fde68a',
    brightBlue: '#93c5fd',
    brightMagenta: '#d8b4fe',
    brightCyan: '#67e8f9',
    brightWhite: '#ffffff'
  }
});

const fitAddon = new FitAddon();
const searchAddon = new SearchAddon();
const terminalHost = document.getElementById('terminal');
terminal.loadAddon(fitAddon);
terminal.loadAddon(searchAddon);
terminal.loadAddon(new WebLinksAddon((event, uri) => {
  event.preventDefault();
  if (/^https?:\/\//i.test(uri)) window.AndroidTerminal?.onOpenLink(uri);
}));
terminal.open(terminalHost);

function bytesToBase64(bytes) {
  let binary = '';
  const chunkSize = 0x8000;
  for (let index = 0; index < bytes.length; index += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunkSize));
  }
  return btoa(binary);
}

function base64ToBytes(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

let selectionMode = false;
let selecting = false;
let selectionAnchor = null;
let touchState = null;
let longPressTimer = 0;
let ctrlArmed = false;
let keepCursorVisibleForIme = false;
let keyboardFocusAllowed = false;
let suppressSyntheticMouseUntil = 0;
/** 本次触摸手势结束时是否请求了键盘：合成 mousedown 不得再 blur 掉刚聚焦的文本框。 */
let lastTouchRequestedKeyboard = false;
let outputGeneration = 0;
const moveThreshold = 12;

function notifySelection() {
  window.AndroidTerminal?.onSelectionChanged(selectionMode, terminal.hasSelection());
}

function setSelectionMode(enabled) {
  selectionMode = enabled;
  selecting = false;
  selectionAnchor = null;
  if (enabled) {
    keepCursorVisibleForIme = false;
    keyboardFocusAllowed = false;
    terminal.clearSelection();
    terminal.blur();
    window.AndroidTerminal?.onHideKeyboard();
  } else {
    terminal.clearSelection();
  }
  notifySelection();
}

function pointFromTouch(touch) {
  const screen = terminal.element?.querySelector('.xterm-screen');
  if (!screen || terminal.cols <= 0 || terminal.rows <= 0) return null;
  const rect = screen.getBoundingClientRect();
  if (touch.clientX < rect.left || touch.clientX > rect.right ||
      touch.clientY < rect.top || touch.clientY > rect.bottom) return null;
  const columnWidth = rect.width / terminal.cols;
  const rowHeight = rect.height / terminal.rows;
  let column = Math.max(0, Math.min(terminal.cols - 1, Math.floor((touch.clientX - rect.left) / columnWidth)));
  const visibleRow = Math.max(0, Math.min(terminal.rows - 1, Math.floor((touch.clientY - rect.top) / rowHeight)));
  const absoluteRow = terminal.buffer.active.viewportY + visibleRow;
  const line = terminal.buffer.active.getLine(absoluteRow);
  let cell = line?.getCell(column);
  while (column > 0 && cell?.getWidth() === 0) {
    column -= 1;
    cell = line?.getCell(column);
  }
  return {
    column,
    absoluteRow,
    visibleRow,
    cellWidth: Math.max(1, cell?.getWidth() || 1),
    rowHeight
  };
}

function urlAtPoint(point) {
  if (!point) return null;
  const text = terminal.buffer.active.getLine(point.absoluteRow)?.translateToString(true) || '';
  const expression = /https?:\/\/[^\s<>"']+/gi;
  for (const match of text.matchAll(expression)) {
    const start = match.index || 0;
    if (point.column >= start && point.column < start + match[0].length) return match[0];
  }
  return null;
}

function updateSelection(focus) {
  if (!selectionAnchor || !focus) return;
  const anchorIndex = selectionAnchor.absoluteRow * terminal.cols + selectionAnchor.column;
  const focusIndex = focus.absoluteRow * terminal.cols + focus.column;
  let start;
  let endIndex;
  if (anchorIndex <= focusIndex) {
    start = selectionAnchor;
    endIndex = focusIndex + focus.cellWidth - 1;
  } else {
    start = focus;
    endIndex = anchorIndex + selectionAnchor.cellWidth - 1;
  }
  terminal.select(start.column, start.absoluteRow, Math.max(1, endIndex - (start.absoluteRow * terminal.cols + start.column) + 1));
  notifySelection();
}

function beginSelection(point) {
  if (!point) return;
  selectionMode = true;
  selecting = true;
  selectionAnchor = point;
  terminal.blur();
  window.AndroidTerminal?.onHideKeyboard();
  updateSelection(point);
}

function clearLongPress() {
  if (longPressTimer) window.clearTimeout(longPressTimer);
  longPressTimer = 0;
}

function handleTouchStart(event) {
  if (event.touches.length !== 1) return;
  event.preventDefault();
  keyboardFocusAllowed = false;
  lastTouchRequestedKeyboard = false;
  suppressSyntheticMouseUntil = performance.now() + 700;
  // 注意：这里不能 blur。一旦 blur，Android 软键盘会随之关闭，随后 onRequestKeyboard
  // 再重新聚焦会造成"先关再开"的闪烁，并与系统 IME 动画竞争导致输入丢失。
  // 键盘只应在真正开始滚动/选择时收起（见 handleTouchMove / beginSelection）。
  const touch = event.touches[0];
  const point = pointFromTouch(touch);
  touchState = {
    startX: touch.clientX,
    startY: touch.clientY,
    lastY: touch.clientY,
    startedAt: performance.now(),
    moved: false,
    maxDistance: 0,
    point
  };
  if (selectionMode) {
    beginSelection(point);
    return;
  }
  clearLongPress();
  longPressTimer = window.setTimeout(() => {
    if (touchState && !touchState.moved) beginSelection(touchState.point);
  }, 450);
}

function handleTouchMove(event) {
  if (!touchState || event.touches.length !== 1) return;
  event.preventDefault();
  const touch = event.touches[0];
  const distance = Math.hypot(touch.clientX - touchState.startX, touch.clientY - touchState.startY);
  touchState.maxDistance = Math.max(touchState.maxDistance, distance);
  if (distance > moveThreshold) {
    touchState.moved = true;
    clearLongPress();
  }
  if (selecting) {
    updateSelection(pointFromTouch(touch));
    return;
  }
  if (touchState.moved) {
    // 用户真正开始滚动：此时收起键盘（focus 属于上一次点击的请求，滚动不再需要）。
    terminal.blur();
    window.AndroidTerminal?.onHideKeyboard();
    const point = touchState.point;
    const rowHeight = point?.rowHeight || 18;
    const rows = Math.trunc((touchState.lastY - touch.clientY) / rowHeight);
    if (rows !== 0) {
      terminal.scrollLines(rows);
      touchState.lastY = touch.clientY;
    }
  }
}

function handleTouchEnd(event) {
  if (!touchState) return;
  event.preventDefault();
  suppressSyntheticMouseUntil = performance.now() + 700;
  clearLongPress();
  const finalTouch = event.changedTouches?.[0];
  if (finalTouch) {
    const finalDistance = Math.hypot(
      finalTouch.clientX - touchState.startX,
      finalTouch.clientY - touchState.startY
    );
    touchState.maxDistance = Math.max(touchState.maxDistance, finalDistance);
    if (finalDistance > moveThreshold) touchState.moved = true;
  }
  const elapsed = performance.now() - touchState.startedAt;
  if (selecting) {
    selecting = false;
    notifySelection();
  } else if (!selectionMode && !touchState.moved && touchState.maxDistance <= moveThreshold &&
      elapsed < 350 && !terminal.hasSelection()) {
    const point = touchState.point;
    const url = urlAtPoint(point);
    if (url) {
      window.AndroidTerminal?.onOpenLink(url);
      touchState = null;
      return;
    }
    const buffer = terminal.buffer.active;
    const cursorAbsoluteRow = buffer.baseY + buffer.cursorY;
    if (point && point.absoluteRow === cursorAbsoluteRow) {
      // 同步置位：随后的合成 mousedown 会据此跳过 blur，保证输入不丢失。
      lastTouchRequestedKeyboard = true;
      window.AndroidTerminal?.onRequestKeyboard();
    }
  }
  touchState = null;
}

terminal.element?.addEventListener('touchstart', handleTouchStart, { passive: false });
terminal.element?.addEventListener('touchmove', handleTouchMove, { passive: false });
terminal.element?.addEventListener('touchend', handleTouchEnd, { passive: false });
terminal.element?.addEventListener('touchcancel', () => {
  clearLongPress();
  selecting = false;
  touchState = null;
}, { passive: false });

// WebView may synthesize a mouse down after a touch gesture. xterm focuses its hidden textarea
// on mouse down, which would otherwise show the IME even after a scroll.
terminal.element?.addEventListener('mousedown', event => {
  if (lastTouchRequestedKeyboard) {
    // 本次点击已请求键盘：保持 textarea 焦点，绝不能 blur（否则键入内容丢失）。
    event.preventDefault();
    event.stopImmediatePropagation();
    lastTouchRequestedKeyboard = false;
    return;
  }
  if (performance.now() < suppressSyntheticMouseUntil || event.sourceCapabilities?.firesTouchEvents) {
    event.preventDefault();
    event.stopImmediatePropagation();
    terminal.blur();
  }
}, true);

terminal.textarea?.addEventListener('focus', () => {
  if (!keyboardFocusAllowed) {
    terminal.blur();
    window.AndroidTerminal?.onHideKeyboard();
  }
}, true);

terminal.onData(data => {
  if (selectionMode) return;
  if (ctrlArmed && data.length > 0) {
    ctrlArmed = false;
    const code = data[0].toUpperCase().charCodeAt(0);
    if (code >= 64 && code <= 95) data = String.fromCharCode(code - 64) + data.slice(1);
    window.AndroidTerminal?.onCtrlArmed(false);
  }
  const encoded = new TextEncoder().encode(data);
  window.AndroidTerminal?.onInput(bytesToBase64(encoded));
});

terminal.onSelectionChange(notifySelection);
searchAddon.onDidChangeResults(result => {
  window.AndroidTerminal?.onSearchResults(result.resultIndex, result.resultCount);
});

let lastColumns = 0;
let lastRows = 0;
function fitAndNotify() {
  try { fitAddon.fit(); } catch (_) { return; }
  if (keepCursorVisibleForIme && !selectionMode) {
    terminal.scrollToBottom();
    terminal.focus();
  }
  if (terminal.cols !== lastColumns || terminal.rows !== lastRows) {
    lastColumns = terminal.cols;
    lastRows = terminal.rows;
    window.AndroidTerminal?.onResize(lastColumns, lastRows);
  }
}

new ResizeObserver(fitAndNotify).observe(terminalHost);
window.visualViewport?.addEventListener('resize', fitAndNotify);

window.sshTerminal = {
  writeBase64(generation, batchId, value) {
    if (generation !== outputGeneration) {
      window.AndroidTerminal?.onOutputProcessed(generation, batchId);
      return;
    }
    terminal.write(base64ToBytes(value), () => {
      window.AndroidTerminal?.onOutputProcessed(generation, batchId);
    });
  },
  resetOutput(generation) {
    outputGeneration = generation;
    setSelectionMode(false);
    searchAddon.clearDecorations();
    terminal.reset();
    terminal.clear();
    window.AndroidTerminal?.onOutputProcessed(generation, -1);
  },
  pasteBase64(value) {
    if (!selectionMode) terminal.paste(new TextDecoder().decode(base64ToBytes(value)));
  },
  focusForIme() {
    if (selectionMode) return;
    keyboardFocusAllowed = true;
    keepCursorVisibleForIme = true;
    terminal.scrollToBottom();
    terminal.focus();
    requestAnimationFrame(fitAndNotify);
  },
  setImeVisible(visible) {
    keepCursorVisibleForIme = !!visible && !selectionMode;
    if (!visible) keyboardFocusAllowed = false;
    if (keepCursorVisibleForIme) requestAnimationFrame(fitAndNotify);
  },
  setAppearance(value) {
    terminal.options.theme = value.theme;
    terminal.options.fontSize = Math.max(10, Math.min(28, value.fontSize || 14));
    document.documentElement.style.background = value.theme.background;
    document.body.style.background = value.theme.background;
    terminalHost.style.background = value.theme.background;
    // 终端背景统一由 --terminal-background 驱动（terminal.css 覆盖 viewport 与内边距区域）。
    document.documentElement.style.setProperty('--terminal-background', value.theme.background);
    requestAnimationFrame(fitAndNotify);
  },
  enterSelectionMode() { setSelectionMode(true); },
  selectAll() {
    selectionMode = true;
    terminal.blur();
    window.AndroidTerminal?.onHideKeyboard();
    terminal.selectAll();
    notifySelection();
  },
  copySelection() {
    const selection = terminal.getSelection();
    if (!selection) return;
    window.AndroidTerminal?.onCopySelection(bytesToBase64(new TextEncoder().encode(selection)));
    setSelectionMode(false);
  },
  clearSelection() { setSelectionMode(false); }
  ,search(value, backwards, caseSensitive) {
    if (!value) {
      searchAddon.clearDecorations();
      window.AndroidTerminal?.onSearchResults(-1, 0);
      return;
    }
    const options = { caseSensitive: !!caseSensitive, decorations: { matchBackground: '#64748b66', activeMatchBackground: '#f59e0b' } };
    if (backwards) searchAddon.findPrevious(value, options); else searchAddon.findNext(value, options);
  }
  ,clearSearch() {
    searchAddon.clearDecorations();
    window.AndroidTerminal?.onSearchResults(-1, 0);
  }
  ,armCtrl() {
    ctrlArmed = !ctrlArmed;
    if (ctrlArmed) terminal.focus();
    window.AndroidTerminal?.onCtrlArmed(ctrlArmed);
  }
};

requestAnimationFrame(() => {
  fitAndNotify();
  terminal.blur();
  window.AndroidTerminal?.onHideKeyboard();
  window.AndroidTerminal?.onReady(terminal.cols, terminal.rows);
});
