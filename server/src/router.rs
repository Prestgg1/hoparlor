// SPDX-License-Identifier: GPL-3.0-or-later
//! Switches the desktop's default output to the Hoparlor sink while a phone is connected,
//! and puts it back afterwards. Uses `pactl`, which talks to pipewire-pulse.

use std::process::Command;
use std::sync::Mutex;

pub const SINK_NAME: &str = "hoparlor_sink";

pub struct Router {
    enabled: bool,
    /// The user's real default sink, saved while we have taken over.
    saved: Mutex<Option<String>>,
}

impl Router {
    pub fn new(enabled: bool) -> Self {
        Self { enabled, saved: Mutex::new(None) }
    }

    /// First phone connected: make Hoparlor the default and move playing apps to it.
    pub fn activate(&self) {
        if !self.enabled {
            return;
        }
        let mut saved = self.saved.lock().unwrap();
        if saved.is_some() {
            return;
        }
        let current = pactl(&["get-default-sink"]).unwrap_or_default();
        // If a previous run crashed while active, the default may still be us.
        if !current.is_empty() && current != SINK_NAME {
            *saved = Some(current);
        } else {
            *saved = Some(String::new());
        }
        let _ = pactl(&["set-default-sink", SINK_NAME]);
        move_all_inputs(SINK_NAME);
        eprintln!("audio output switched to the phone");
    }

    /// Last phone gone: restore the previous default and move apps back to it.
    pub fn deactivate(&self) {
        let Some(prev) = self.saved.lock().unwrap().take() else { return };
        if prev.is_empty() {
            return;
        }
        let _ = pactl(&["set-default-sink", &prev]);
        move_all_inputs(&prev);
        eprintln!("audio output restored to {prev}");
    }
}

fn pactl(args: &[&str]) -> Option<String> {
    let out = Command::new("pactl").args(args).output().ok()?;
    out.status
        .success()
        .then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
}

fn move_all_inputs(sink: &str) {
    let Some(list) = pactl(&["list", "short", "sink-inputs"]) else { return };
    for id in list.lines().filter_map(|l| l.split_whitespace().next()) {
        let _ = pactl(&["move-sink-input", id, sink]);
    }
}
