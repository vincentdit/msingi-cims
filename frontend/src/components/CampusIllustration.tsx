import { Box } from '@mui/material';

/**
 * A drawing of a college campus, shown on the portal until the institution uploads a photo of its own.
 * Drawn in the institution's colours so it sits with the rest of the page.
 */
export function CampusIllustration({ primary, accent, name }: { primary: string; accent: string; name: string }) {
  const windows = (x0: number, y0: number, cols: number, rows: number) =>
    Array.from({ length: cols * rows }, (_, i) => (
      <rect key={`${x0}-${i}`} x={x0 + (i % cols) * 26} y={y0 + Math.floor(i / cols) * 30} width="16" height="18" rx="2"
        fill={(i * 7) % 5 === 0 ? '#FFE7A8' : '#CFE0F2'} />
    ));
  return (
    <Box component="svg" viewBox="0 0 560 380" role="img" aria-label={`Illustration of ${name}`} sx={{ display: 'block', width: '100%', height: '100%' }}
      preserveAspectRatio="xMidYMid slice">
      <defs>
        <linearGradient id="ci-sky" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#BFE0F5" />
          <stop offset="1" stopColor="#F3F8FC" />
        </linearGradient>
        <linearGradient id="ci-wall" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#FFFFFF" />
          <stop offset="1" stopColor="#E6ECF2" />
        </linearGradient>
      </defs>
      <rect width="560" height="380" fill="url(#ci-sky)" />
      <circle cx="470" cy="70" r="30" fill="#FFD66B" opacity="0.9" />
      {/* hills */}
      <path d="M0 250 Q90 190 190 232 T380 214 T560 228 V380 H0Z" fill="#9FC48A" />
      <path d="M0 272 Q140 236 280 262 T560 254 V380 H0Z" fill="#7FB06A" />
      {/* side wings */}
      <rect x="60" y="178" width="140" height="112" fill="url(#ci-wall)" />
      <rect x="360" y="178" width="140" height="112" fill="url(#ci-wall)" />
      <rect x="56" y="170" width="148" height="10" fill={primary} />
      <rect x="356" y="170" width="148" height="10" fill={primary} />
      {windows(76, 192, 5, 3)}
      {windows(376, 192, 5, 3)}
      {/* main block */}
      <rect x="196" y="132" width="168" height="158" fill="url(#ci-wall)" />
      <path d="M186 136 L280 92 L374 136 Z" fill={primary} />
      <circle cx="280" cy="120" r="9" fill={accent} />
      {windows(212, 150, 6, 2)}
      {/* entrance with columns */}
      <rect x="226" y="214" width="108" height="8" fill={primary} opacity="0.85" />
      {[234, 256, 296, 318].map((x) => <rect key={x} x={x} y="222" width="8" height="64" fill="#F7F9FB" stroke="#D5DEE7" />)}
      <rect x="266" y="236" width="28" height="54" rx="3" fill={primary} opacity="0.9" />
      <rect x="216" y="286" width="128" height="6" fill="#C9D3DC" />
      {/* flag */}
      <rect x="140" y="88" width="3" height="90" fill="#6B7785" />
      <g transform="translate(143 90)">
        <rect width="40" height="26" fill="#1EB53A" />
        <path d="M0 26 L40 0 V26Z" fill="#00A3DD" />
        <path d="M0 22 L33 0 H40 V4 L7 26 H0Z" fill="#FCD116" />
        <path d="M0 24 L36 0 H38 L2 26 H0Z" fill="#000" />
      </g>
      {/* path and acacias */}
      <path d="M266 292 L294 292 L340 380 L220 380 Z" fill="#E9DFC9" />
      {[[30, 262, 1], [520, 266, 1.1], [218, 300, 0.7], [352, 302, 0.75]].map(([x, y, k]) => (
        <g key={x} transform={`translate(${x} ${y}) scale(${k})`}>
          <rect x="-3" y="-6" width="6" height="34" fill="#6D5A44" />
          <ellipse cx="0" cy="-12" rx="34" ry="11" fill="#4E8C46" />
          <ellipse cx="-10" cy="-20" rx="20" ry="7" fill="#5DA153" />
        </g>
      ))}
    </Box>
  );
}
