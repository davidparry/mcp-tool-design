import { defineConfig } from '@playwright/test'

const PORT = 8090

// Runs against the app jar with the frontend bundled in it, so the test exercises what ships.
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `java -jar ../app/target/app-0.1.0-SNAPSHOT.jar --server.port=${PORT}`,
    url: `http://localhost:${PORT}/v3/api-docs`,
    reuseExistingServer: false,
    timeout: 60_000,
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
})
