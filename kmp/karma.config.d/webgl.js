config.set({
    client: {
        mocha: {
            timeout: 900000,
        },
    },
    browserNoActivityTimeout: 900000,
    captureTimeout: 120000,
    customLaunchers: {
        ChromeHeadlessNoSandbox: {
            base: "Chrome",
            flags: [
                "--headless=new",
                "--no-sandbox",
                "--enable-gpu",
                "--ignore-gpu-blocklist",
            ],
        },
    },
});
