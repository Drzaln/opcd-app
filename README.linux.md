# Using OpenCode Mobile with a Linux server

Same app, same protocol — only the **server host** changes. If opencode runs on Linux instead of a
Mac, follow this instead of section 1 of the main [README](README.md).

There are two setups:

| Your Linux box | Run this | Why |
|---|---|---|
| **Desktop** (you also use the terminal TUI) | the **TUI** with a fixed port | terminal + phone share one server → live sync both ways |
| **Headless server** (app-only, no TUI) | `opencode serve` | no TUI to sync with; headless server is the right choice |

---

## 1. Install opencode

```bash
curl -fsSL https://opencode.ai/install | bash
# or: npm install -g opencode-ai
# or: brew install sst/tap/opencode
opencode --version
```

## 2. Tailscale

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
tailscale ip -4          # e.g. 100.111.24.2
```

## 3. Run the server

**Desktop Linux — TUI as server (recommended if you also use the terminal):**

```bash
OPENCODE_SERVER_PASSWORD=your-secret \
  opencode --hostname 100.111.24.2 --port 4096
```

**Headless server — dedicated server process:**

```bash
OPENCODE_SERVER_PASSWORD=your-secret \
  opencode serve --hostname 100.111.24.2 --port 4096
```

- `--hostname 100.x.y.z` → listens **only** on Tailscale, not your LAN.
- `--port 4096` → the port the phone connects to.
- `OPENCODE_SERVER_PASSWORD` → required; without it the server returns `401`.

### Keep the machine from sleeping

Linux has no `caffeinate`. Pick one:

```bash
# While a command runs (idle + sleep inhibited):
systemd-inhibit --what=idle:sleep --why="opencode server" \
  env OPENCODE_SERVER_PASSWORD=your-secret opencode --hostname 100.111.24.2 --port 4096

# Temporary (until reboot) — blanket disable of suspend on a server:
sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
```

### Optional — HTTPS with Tailscale Serve

```bash
tailscale serve --bg 4096
```
Then connect the app to `https://<hostname>.ts.net`.

### Optional — mDNS on the same LAN

Needs Avahi:
```bash
sudo apt install avahi-daemon       # Debian/Ubuntu
sudo systemctl enable --now avahi-daemon
OPENCODE_SERVER_PASSWORD=your-secret opencode --hostname 0.0.0.0 --port 4096 --mdns
```
(`--mdns` forces `0.0.0.0`, i.e. also reachable on the LAN — fine, the password still protects it.)

---

## 4. Firewall

The server binds to the Tailscale interface, but a host firewall can still block it. Allow the port
**only on the Tailscale interface**:

```bash
# ufw (Debian/Ubuntu)
sudo ufw allow in on tailscale0 to any port 4096 proto tcp

# firewalld (Fedora/RHEL)
sudo firewall-cmd --permanent --add-interface=tailscale0 --zone=trusted
sudo firewall-cmd --permanent --zone=trusted --add-port=4096/tcp
sudo firewall-cmd --reload

# nftables/iptables: accept tcp dport 4096 on the tailscale0 interface only
```

Verify from the Mac/phone: `curl -u opencode:your-secret http://100.111.24.2:4096/global/health`

---

## 5. Run it as a service (always-on)

`/etc/systemd/system/opencode.service`:

```ini
[Unit]
Description=opencode server
After=network-online.target tailscaled.service
Wants=network-online.target

[Service]
User=YOUR_USER
Environment=OPENCODE_SERVER_PASSWORD=your-secret
ExecStart=/usr/local/bin/opencode serve --hostname 100.111.24.2 --port 4096
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now opencode
systemctl status opencode
```

> `.ini` note: if your opencode is installed elsewhere (e.g. `~/.opencode/bin/opencode` or via nvm),
> put the full path in `ExecStart`. For nvm, add `Environment=PATH=/home/YOUR_USER/.nvm/versions/node/vX/bin:/usr/bin:/bin`.

---

## 6. Connect from the phone

1. Tailscale **on** on the phone.
2. App → **Connect to server** → **Tailscale (remote)**.
3. Host `100.111.24.2` (or `my-server.ts.net` for `tailscale serve`), port `4096`, password.
4. **Connect** → pick a project folder on the Sessions screen.

---

## Notes & differences vs macOS

- **Live TUI sync** only exists if you run the TUI with `--port` (desktop case). With `opencode serve`
  the app has its own session/event stream — sends from the app won't appear live in a terminal
  (there is no terminal).
- Session data lives under `~/.local/share/opencode` (state) and `~/.config/opencode` (config) for the
  user that runs the server. Run the service as that **same user** so sessions are shared.
- **SELinux** (Fedora/RHEL): if the service can't bind, check `sudo ausearch -m avc -ts recent` and set
  the right context, or run it as a user service (`systemctl --user`) instead of system-wide.
- Remote access goes over Tailscale only — nothing is exposed to the internet.

See the main [README](README.md) for app features, usage, and troubleshooting.
