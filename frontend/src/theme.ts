export type ThemePreference = "system" | "light" | "dark";

const STORAGE_KEY = "whyfi-theme";

export function getThemePreference(): ThemePreference {
  const stored = localStorage.getItem(STORAGE_KEY);
  return stored === "light" || stored === "dark" ? stored : "system";
}

function resolveTheme(preference: ThemePreference): "light" | "dark" {
  if (preference === "system") {
    return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
  }
  return preference;
}

export function applyTheme(preference: ThemePreference = getThemePreference()): void {
  document.documentElement.dataset.theme = resolveTheme(preference);
}

export function setThemePreference(preference: ThemePreference): void {
  if (preference === "system") {
    localStorage.removeItem(STORAGE_KEY);
  } else {
    localStorage.setItem(STORAGE_KEY, preference);
  }
  applyTheme(preference);
}

// Keeps the page in sync if the OS theme changes while "system" is selected
// and this tab is still open.
export function watchSystemTheme(): void {
  window.matchMedia("(prefers-color-scheme: light)").addEventListener("change", () => {
    if (getThemePreference() === "system") applyTheme();
  });
}
