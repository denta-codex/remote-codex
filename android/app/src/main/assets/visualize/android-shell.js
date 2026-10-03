// Trusted outer document. Only this shell receives the native port; authored HTML
// runs in an opaque-origin iframe without Android interfaces or application data.
(() => {
  document.documentElement.dataset.visualizationStage = 'shell';
  let nativePort;
  const frame = document.getElementById('visualization-frame');
  const channel = new MessageChannel();
  let lastHeight = 240;
  let frameReady = false;
  let initialized = false;
  const initialize = () => {
    if (!nativePort || !frameReady || initialized) return;
    initialized = true;
    document.documentElement.dataset.visualizationStage = 'connected';
    frame.contentWindow.postMessage({type: 'codex-visualization-initialize'}, '*', [channel.port2]);
  };
  const post = (value) => nativePort?.postMessage(JSON.stringify(value));
  window.addEventListener('message', (event) => {
    // Android's postWebMessage has a null source; iframe messages have a WindowProxy.
    if (event.source !== null || !event.isTrusted || event.data !== 'remote-codex-visualization' ||
        nativePort || event.ports.length !== 1) return;
    nativePort = event.ports[0];
    document.documentElement.dataset.visualizationStage = 'native';
    nativePort.onmessage = (message) => {
      try {
        const value = JSON.parse(message.data);
        if (value.type === 'widget-state-result') channel.port1.postMessage(value);
      } catch {}
    };
    post({ type: 'height', height: lastHeight });
    initialize();
  });
  channel.port1.onmessage = (event) => {
    const data = event.data;
    if (!data || typeof data !== 'object') return;
    if (data.type === 'height' && Number.isFinite(data.height) && data.height >= 0 && data.height <= 10000) {
      lastHeight = Math.max(48, Math.ceil(data.height));
      frame.style.height = lastHeight + 'px';
      post({ type: 'height', height: lastHeight });
    } else if (data.type === 'widget-state-write' && Number.isSafeInteger(data.id) &&
        data.id > 0 && typeof data.state === 'string' && new TextEncoder().encode(data.state).length <= 16384) {
      post({ type: 'widget-state-write', id: data.id, state: data.state });
    } else if (data.type === 'follow-up' && typeof data.prompt === 'string' &&
        data.prompt.length <= 16384 && navigator.userActivation?.isActive) {
      post({ type: 'follow-up', prompt: data.prompt, title: typeof data.title === 'string' ? data.title.slice(0, 250) : '' });
    } else if (data.type === 'open-external' && typeof data.href === 'string' &&
        data.href.length <= 4096 && navigator.userActivation?.isActive) {
      post({ type: 'open-external', href: data.href });
    } else if (data.type === 'scroll-to' && Number.isFinite(data.top) && data.top >= 0 &&
        navigator.userActivation?.isActive) {
      window.scrollTo(0, Math.min(data.top, lastHeight));
    }
  };
  frame.addEventListener('load', () => {
    frameReady = true;
    document.documentElement.dataset.visualizationStage = 'frame';
    initialize();
  }, {once: true});
  frame.srcdoc = frame.dataset.srcdoc;
  delete frame.dataset.srcdoc;
})();
