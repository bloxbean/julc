import { writable } from 'svelte/store';

/** Top-level page: the JuLC contract playground, or tools for any compiled UPLC script. */
export type PlaygroundMode = 'contract' | 'uplc';

const KEY = 'julc.playground.mode';

function initial(): PlaygroundMode {
  const fromUrl = new URLSearchParams(window.location.search).get('mode');
  if (fromUrl === 'uplc' || fromUrl === 'contract') return fromUrl;
  try {
    const stored = localStorage.getItem(KEY);
    if (stored === 'uplc' || stored === 'contract') return stored;
  } catch {
    // storage unavailable
  }
  return 'contract';
}

export const mode = writable<PlaygroundMode>(initial());

mode.subscribe((value) => {
  try {
    localStorage.setItem(KEY, value);
  } catch {
    // storage unavailable
  }
});
