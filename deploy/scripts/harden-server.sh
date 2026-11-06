#!/bin/bash
# One-off hardening of a fresh Ubuntu server for CIMS (run as root; safe to run again):
#   swap (2 GB when there is none), firewall (SSH, HTTP, HTTPS only), automatic security updates, fail2ban for SSH.
# Docker publishes only 80 and 443 (the proxy); the database and backend are not reachable from outside.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }

if ! swapon --show | grep -q .; then
  echo "== swap: 2 GB at /swapfile"
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  sysctl -q vm.swappiness=10 && echo 'vm.swappiness=10' > /etc/sysctl.d/90-cims-swap.conf
else
  echo "== swap already on"; swapon --show
fi

echo "== packages"
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ufw fail2ban unattended-upgrades >/dev/null

echo "== firewall: SSH, HTTP, HTTPS"
ufw allow OpenSSH >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null
ufw status verbose | sed -n 1,12p

echo "== fail2ban for SSH"
cat > /etc/fail2ban/jail.d/cims-sshd.local <<'JAIL'
[sshd]
enabled = true
maxretry = 5
findtime = 10m
bantime = 1h
JAIL
systemctl enable --now fail2ban >/dev/null && systemctl restart fail2ban

echo "== automatic security updates"
dpkg-reconfigure -f noninteractive unattended-upgrades >/dev/null
systemctl enable --now unattended-upgrades >/dev/null

echo "== done. Reboot needed: $( [ -f /var/run/reboot-required ] && echo yes || echo no )"
