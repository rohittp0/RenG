// Webpack's web-target runtime reads document.baseURI even when importScripts is available.
// Supplying only that property keeps `window` absent, so the shared bundle enters its worker path.
self.document = { baseURI: self.location.href };
// The page posts the canvas at once, but Kotlin's main() only runs after the Wasm modules load; a
// message that arrives before a listener exists is dropped. Hold it only for the render-worker role.
// Tile workers install their request listener from Kotlin after the shared bundle has loaded.
if (new URL(self.location.href).searchParams.get('role') === 'tile') {
  self.__rengTileMessages = [];
  self.__rengTileHold = (event) => self.__rengTileMessages.push(event.data);
  self.addEventListener('message', self.__rengTileHold);
} else {
  self.__rengSetup = new Promise((resolve) => self.addEventListener('message', (event) => resolve(event.data), { once: true }));
}
importScripts(new URL('web-demo.js', self.location.href));
