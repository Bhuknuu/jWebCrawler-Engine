 jWebCrawler-Engine

jWebCrawler-Engine is a high-performance, domain-bounded Web Crawler built in Java, featuring a real-time, interactive, force-directed graph visualization dashboard. It uses a Breadth-First Search (BFS) traversal strategy constrained by depth limits, domain scoping, and hard page caps, making it safe and robust for crawling the web.

  Features

- **Domain-Bounded BFS Engine:** Performs level-order traversal, strictly staying within the seed URL's domain to prevent unbounded crawling.
- **Real-Time Visualization Dashboard:** A beautiful, neo-brutalist web dashboard running at `http://localhost:8080`. Powered by **Cytoscape.js** and **WebCola**, it visualizes the live topology of the crawl as a force-directed graph.
- **Memory-Efficient Deduplication:** Uses a custom-built **Bloom Filter Layer** for lightning-fast, memory-efficient visited-URL lookups.
- **Politeness & Safety Controls:** Built-in delays (politeness) between requests and automatic parsing of `robots.txt` to respect website crawling rules.
- **Keyword Matching:** Search for specific terms during the crawl, and the engine will flag and highlight matching pages on the dashboard.
- **Zero-Config Setup:** No CLI arguments needed. Configure the seed URL, max depth, page cap, and keywords directly from the browser UI.

 Tech Stack

- **Backend / Crawler Engine:** Java (Standard Library - `java.net.http`, `java.util.concurrent`)
- **Frontend Dashboard:** HTML5, CSS3, Vanilla JavaScript
- **Graph Visualization:** Cytoscape.js, WebCola
- **Build System:** PowerShell (`build.ps1`)

 Project Structure

```text
jWebCrawler-Engine/
├── Project/
│   └── src/                  # Java Source files (Crawler Engine, Bloom Filter, Server, etc.)
├── dashboard/                # Frontend assets (HTML, CSS, JS) for the live dashboard
├── Documentation/            # Detailed technical documentation and reports
└── build.ps1                 # Build and run script
```

 How to Build and Run

 Prerequisites
- **Java 11+** installed and added to your system `PATH`.
- **PowerShell** (available by default on Windows).

 Running the Project

1. Open PowerShell and navigate to the project directory.
2. Run the build script:
   ```powershell
   .\build.ps1
   ```
3. The script will compile the Java sources into `Project/src/out/` and immediately start the Dashboard Server.
4. Your default web browser will automatically open to `http://localhost:8080`.
5. From the dashboard, enter a **Seed URL**, configure your parameters, and click **Start Crawl**!

*(To only compile the project without running it, use: `.\build.ps1 -compile`)*

 Documentation

For an in-depth look at the architecture, design choices, and data structures (like the Bloom Filter integration), please check the files inside the `Documentation/` directory:
- `jWebCrawler_DeepDive.html`
- `jWebCrawler_Documentation.html`
- `DAA Phase2_report.pdf`

## 🛡️ Graceful Shutdown

To stop the crawler and the dashboard server safely, simply press `Ctrl+C` in the terminal. The JVM shutdown hook will ensure all data is flushed and resources are closed properly.
