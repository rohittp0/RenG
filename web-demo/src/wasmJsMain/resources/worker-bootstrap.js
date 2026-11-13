// Webpack's web-target runtime reads document.baseURI even when importScripts is available.
// Supplying only that property keeps `window` absent, so the shared bundle enters its worker path.
self.document = { baseURI: self.location.href };
// The page posts the canvas at once, but Kotlin's main() only runs after the Wasm modules load; a
// message that arrives before a listener exists is dropped. Hold the first one until main() asks.
self.__rengSetup = new Promise((resolve) => self.addEventListener('message', (event) => resolve(event.data), { once: true }));
importScripts(new URL('web-demo.js', self.location.href));
