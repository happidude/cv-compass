import { readdirSync, readFileSync, createReadStream } from "node:fs";
import { fileURLToPath } from "node:url";
import type { Plugin } from "vite";

/** Only dependency-owned asset names are served; no request-derived filesystem path. */
export function pdfAssets(): Plugin {
  const files = new Map<string, string>();
  files.set(
    "/pdfjs-assets/LICENSE",
    fileURLToPath(
      new URL("./node_modules/pdfjs-dist/LICENSE", import.meta.url),
    ),
  );
  for (const group of ["cmaps", "standard_fonts", "wasm", "iccs"]) {
    const root = new URL(
      "./node_modules/pdfjs-dist/" + group + "/",
      import.meta.url,
    );
    for (const name of readdirSync(root))
      files.set(
        "/pdfjs-assets/" + group + "/" + name,
        fileURLToPath(new URL(name, root)),
      );
  }
  let building = false;
  return {
    name: "local-pdfjs-assets",
    configResolved(config) {
      building = config.command === "build";
    },
    buildStart() {
      if (building)
        for (const [url, file] of files)
          this.emitFile({
            type: "asset",
            fileName: url.slice(1),
            source: readFileSync(file),
          });
    },
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const file = files.get((request.url ?? "").split("?")[0]);
        if (!file) return next();
        response.setHeader(
          "Content-Type",
          file.endsWith(".wasm")
            ? "application/wasm"
            : file.endsWith(".js")
              ? "text/javascript"
              : "application/octet-stream",
        );
        const stream = createReadStream(file);
        stream.on("error", () => {
          if (!response.headersSent) response.statusCode = 404;
          response.end();
        });
        stream.pipe(response);
      });
    },
  };
}
