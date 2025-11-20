# Netty-SocketIO Documentation

This directory contains the Jekyll-based documentation site for Netty-SocketIO.

## Local Development

### Prerequisites

- Docker and Docker Compose installed

### Running Locally with Docker

The easiest way to develop and preview the documentation locally is using Docker:

```bash
cd docs
docker-compose up
```

The site will be available at `http://localhost:4000/netty-socketio/`

### Alternative: Using docker run

If you prefer using `docker run` directly:

```bash
# From the project root directory
docker run --rm -it -v "$PWD/docs:/srv/jekyll" -p 4000:4000 jekyll/jekyll:latest jekyll serve --host 0.0.0.0
```

### Building for Production

To build the site without running a server:

```bash
cd docs
docker-compose run --rm jekyll jekyll build
```

The built site will be in the `_site` directory.

## Structure

- `_config.yml` - Jekyll configuration
- `index.md` - Homepage
- `getting-started.md` - Quick start guide
- `installation.md` - Installation instructions
- `configuration.md` - Configuration options
- `examples/` - Code examples
- `guides/` - Integration guides
- `api/` - API documentation
- `performance.md` - Performance reports
- `contributing.md` - Contributing guide

## Theme

This site uses the [Just the Docs](https://just-the-docs.github.io/just-the-docs/) Jekyll theme, which provides:

- Clean, professional design
- Responsive layout
- Search functionality
- Code syntax highlighting
- Sidebar navigation

## Deployment

The site is automatically deployed to GitHub Pages via GitHub Actions when changes are pushed to the `main` branch.

See `.github/workflows/gh-pages.yml` for the deployment configuration.
