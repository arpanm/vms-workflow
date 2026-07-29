process.env.VMS_E2E_SYSTEM_SUITE = "f07";
process.env.VMS_E2E_SYSTEM_PROJECT =
  "f07-delivery-confirmation-system-chromium";
await import("./run-finance-system-e2e.mjs");
