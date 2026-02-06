# EverNear - Health Monitoring App

## Overview
EverNear is a health monitoring mobile application (originally Android/Java) that tracks vital signs via smartwatch and sends emergency alerts to caregivers. This Replit project provides an interactive web-based preview of the app's UI.

## Recent Changes
- 2026-02-06: Initial setup - created web preview of Android app using Node.js/Express

## Project Architecture
- **Original Platform:** Android (Java, Gradle, Firebase Auth/Firestore)
- **Web Preview:** Node.js + Express serving static HTML/CSS/JS on port 5000
- **Screens:** Main (role selection), Login, Patient (heart monitor), Caregiver (alert dashboard)

### Key Files
- `server.js` - Express server (port 5000)
- `public/index.html` - Web preview HTML
- `public/styles.css` - Dark theme styling matching Android app
- `public/app.js` - Screen navigation and heart rate simulation
- `app/` - Original Android source code (Java, XML layouts, resources)

### Stack
- Node.js 20 with Express
- Static HTML/CSS/JS frontend
- Original Android code preserved in `app/` directory

## User Preferences
- Language: Portuguese (Brazilian) - app UI is in pt-BR
