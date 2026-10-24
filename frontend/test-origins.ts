import { createServer, type Server } from "node:net";

/** Reserve distinct ephemeral loopback ports for browser test servers, then release them. */
export async function testOrigins(count: number): Promise<string[]> {
  // Playwright loads config again in each worker; inherit the coordinator's chosen ports.
  const key = `CV_COMPASS_TEST_ORIGINS_${count}`;
  const pinned = process.env[key];
  if (pinned) {
    const origins: unknown = JSON.parse(pinned);
    if (
      !Array.isArray(origins) ||
      origins.length !== count ||
      origins.some((origin) => typeof origin !== "string")
    )
      throw new Error("Invalid inherited test origins");
    return origins;
  }
  const servers: Server[] = [];
  try {
    const origins: string[] = [];
    for (let i = 0; i < count; i++) {
      const server = createServer();
      servers.push(server);
      await new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", resolve);
      });
      const address = server.address();
      if (!address || typeof address === "string")
        throw new Error("Missing test TCP address");
      origins.push(`http://127.0.0.1:${address.port}`);
    }
    process.env[key] = JSON.stringify(origins);
    return origins;
  } finally {
    await Promise.all(
      servers.map(
        (server) =>
          new Promise<void>((resolve) => server.close(() => resolve())),
      ),
    );
  }
}
