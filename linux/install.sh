#!/usr/bin/env bash
# Installiert den Linux-Client nur für den aktuellen Benutzer (nichts außerhalb von ~/.local).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
share="${XDG_DATA_HOME:-$HOME/.local/share}"
app="$share/notizen-linux/app"
bin="$HOME/.local/bin"

python3 -c "import gi; gi.require_version('Gtk','4.0'); gi.require_version('Adw','1')" 2>/dev/null \
  || { echo "GTK 4, libadwaita und PyGObject fehlen (Arch: sudo pacman -S python-gobject gtk4 libadwaita)" >&2; exit 1; }

mkdir -p "$app" "$bin" "$share/applications"
rm -rf "$app/notizen_linux"
cp -r "$here/notizen_linux" "$app/"
find "$app" -name __pycache__ -type d -prune -exec rm -rf {} +

cat > "$bin/notizen-linux" <<EOF
#!/usr/bin/env bash
cd "$app" && exec python3 -m notizen_linux "\$@"
EOF
chmod +x "$bin/notizen-linux"

cat > "$share/applications/notizen-linux.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Notizen
Comment=Notizen mit Offline-Kopie und Abgleich
Exec=$bin/notizen-linux
Icon=accessories-text-editor
Categories=Office;TextEditor;
StartupWMClass=online.minihomelab.NotizenLinux
Actions=Capture;

[Desktop Action Capture]
Name=Idee festhalten
Exec=$bin/notizen-linux capture
EOF

echo "Fertig. Starten mit: notizen-linux (oder im Anwendungsmenü: Notizen)"
case ":$PATH:" in *":$bin:"*) ;; *) echo "Hinweis: $bin ist nicht in deinem PATH." ;; esac
