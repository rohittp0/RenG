// Webpack's web-target runtime reads document.baseURI even when importScripts is available.
self.document = { baseURI: self.location.href };
// Requests can arrive before Kotlin/Wasm has loaded and installed its message handler.
self.__rengTileMessages = [];
self.__rengTileHold = (event) => self.__rengTileMessages.push(event.data);
self.addEventListener('message', self.__rengTileHold);
importScripts(new URL('web-tile-worker.js', self.location.href));
