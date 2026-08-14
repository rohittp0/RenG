(() => {
  const nodes = document.querySelectorAll('[data-maven-version="kmp"]');
  if (nodes.length === 0) return;

  fetch(
    "https://maven.rohittp.com/com/rohittp/reng/kmp/maven-metadata.xml",
    { cache: "no-store" },
  )
    .then((response) => {
      if (!response.ok) {
        throw new Error(`Metadata request failed with HTTP ${response.status}`);
      }
      return response.text();
    })
    .then((metadata) => {
      const xml = new DOMParser().parseFromString(metadata, "application/xml");
      if (xml.querySelector("parsererror")) {
        throw new Error("Metadata response is not valid XML");
      }
      const release = xml.querySelector("versioning > release")?.textContent?.trim();
      if (!release) {
        throw new Error("Metadata does not contain a release version");
      }
      nodes.forEach((node) => {
        node.textContent = release;
      });
    })
    .catch((error) => {
      console.error("Unable to load the published RenG version.", error);
    });
})();
