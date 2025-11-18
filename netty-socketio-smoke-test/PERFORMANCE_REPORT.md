# Netty SocketIO Performance Test Report

This report contains daily performance test results for Netty SocketIO.

## Test Configuration
- Server Port: 8899
- Client Count: 10
- Messages per Client: 50000
- Message Size: 32 bytes
- Server Max Memory: 256 MB

## Test Results

*Results will be automatically updated daily by GitHub Actions*

---

## Historical Results

| Date | Java Version | OS | CPU Cores | Messages/sec | Avg Latency (ms) | P99 Latency (ms) | Error Rate (%) | Max Heap (MB) | JVM Args | Git Branch | Version | Test Duration (ms) |
|------|-------------|----|-----------|--------------|------------------|------------------|----------------|---------------|-----------|------------|---------|-------------------|
| 2025-11-18 18:52:27 | 23.0.1 | Mac OS X 26.1 | 8 | 373,412.99 | 369.91 | 591 | 0.0000 | 256 | -Xms256m -Xmx256m -XX:+UseG1GC -XX:+AlwaysPreTouch | auto-nativeio-transport | 3.0.2-SNAPSHOT | 1339 |
| 2025-11-11 07:46:28 | 25.0.1 | Linux 6.11.0-1018-azure | 4 | 220,264.32 | 1308.03 | 1911 | 0.0000 | 256 | -Xms256m -Xmx256m -XX:+UseG1GC -XX:+AlwaysPreTouch | v3.x | 3.0.1 | 2270 |
| 2025-11-11 07:41:07 | 21.0.9 | Linux 6.11.0-1018-azure | 4 | 198,886.24 | 1399.18 | 1975 | 0.0000 | 256 | -Xms256m -Xmx256m -XX:+UseG1GC -XX:+AlwaysPreTouch | v3.x | 3.0.1 | 2514 |
| 2025-11-11 07:31:13 | 17.0.17 | Linux 6.11.0-1018-azure | 4 | 202,593.19 | 1481.27 | 2159 | 0.0000 | 256 | -Xms256m -Xmx256m -XX:+UseG1GC -XX:+AlwaysPreTouch | v3.x | 3.0.1 | 2468 |
| 2025-11-11 07:25:31 | 11.0.29 | Linux 6.11.0-1018-azure | 4 | 181,028.24 | 1618.49 | 2239 | 0.0000 | 256 | -Xms256m -Xmx256m -XX:+UseG1GC -XX:+AlwaysPreTouch | v3.x | 3.0.1 | 2762 |
| 2025-10-16 00:48:47 | 25 | Linux 6.11.0-1018-azure | 4 | 224,618.15 | 1142.70 | 1743 | 0.0000 | 256 | -Xms256m -Xmx256m -XX:+UseG1GC -XX:+AlwaysPreTouch | v3.x | 3.0.0 | 2226 |
| 2025-10-16 00:45:19 | 21.0.8 | Linux 6.11.0-1018-azure | 4 | 206,270.63 | 1359.11 | 2007 | 0.0000 | 256 | -Xms256m -Xmx256m -XX:+UseG1GC -XX:+AlwaysPreTouch | v3.x | 3.0.0 | 2424 |
| 2025-10-16 00:29:15 | 17.0.16 | Linux 6.11.0-1018-azure | 4 | 194,476.86 | 1554.77 | 2191 | 0.0000 | 256 | -Xms256m -Xmx256m -XX:+UseG1GC -XX:+AlwaysPreTouch | v3.x | 3.0.0 | 2571 |
| 2025-10-16 00:27:04 | 11.0.28 | Linux 6.11.0-1018-azure | 4 | 186,567.16 | 1566.61 | 2255 | 0.0000 | 256 | -Xms256m -Xmx256m -XX:+UseG1GC -XX:+AlwaysPreTouch | v3.x | 3.0.0 | 2680 |
