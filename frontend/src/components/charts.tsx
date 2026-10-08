import { Box, Stack, Tooltip, Typography, useTheme } from '@mui/material';
import { useState, type ReactNode } from 'react';

/**
 * Small, dependency-free charts for dashboards. One series each, one hue (the institution's primary colour) for magnitude;
 * thin marks with a rounded data end, recessive axes, values as text in ink colours, hover tooltips on every mark.
 */

export interface BarDatum { key: string; label: ReactNode; value: number; display?: string; note?: string }

/** Horizontal bars for comparing categories (sorted by the caller). */
export function BarList({ rows, max, emptyText = 'Nothing yet.' }: { rows: BarDatum[]; max?: number; emptyText?: string }) {
  const top = max ?? Math.max(1, ...rows.map((r) => r.value));
  if (!rows.length) return <Typography variant="body2" color="text.secondary">{emptyText}</Typography>;
  return (
    <Stack spacing={1.25} role="list">
      {rows.map((r) => (
        <Tooltip key={r.key} title={r.note ?? ''} placement="top-start" disableHoverListener={!r.note}>
          <Box role="listitem">
            <Stack direction="row" alignItems="baseline" spacing={1}>
              <Typography variant="body2" sx={{ flexGrow: 1, minWidth: 0 }} noWrap>{r.label}</Typography>
              <Typography variant="body2" sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{r.display ?? r.value.toLocaleString()}</Typography>
            </Stack>
            <Box sx={{ mt: 0.5, height: 8, borderRadius: 1, bgcolor: 'action.hover' }}>
              <Box sx={{ height: 8, width: `${Math.max(r.value > 0 ? 1.5 : 0, (100 * r.value) / top)}%`, bgcolor: 'primary.main', borderRadius: '0 4px 4px 0',
                transition: 'width .3s' }} />
            </Box>
          </Box>
        </Tooltip>
      ))}
    </Stack>
  );
}

/** Columns over time (one per day), with a hover tooltip and the peak value marked on a single recessive gridline. */
export function ColumnChart({ points, format = (v) => v.toLocaleString(), height = 140, ariaLabel }: {
  points: { key: string; label: string; value: number; tooltip?: string }[]; format?: (v: number) => string; height?: number; ariaLabel: string;
}) {
  const theme = useTheme();
  const [hover, setHover] = useState<number | null>(null);
  const max = Math.max(1, ...points.map((p) => p.value));
  const W = 600, H = height, top = 16, bottom = 22, n = Math.max(points.length, 1);
  const slot = W / n, bar = Math.min(18, slot * 0.66), plot = H - top - bottom;
  const every = Math.ceil(n / 8);
  const ink = theme.palette.text.secondary, grid = theme.palette.divider;
  const h = hover != null ? points[hover] : null;
  return (
    <Box sx={{ position: 'relative' }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label={ariaLabel} style={{ display: 'block', overflow: 'visible' }}
        onMouseLeave={() => setHover(null)}>
        <line x1={0} x2={W} y1={top} y2={top} stroke={grid} strokeDasharray="3 4" />
        <text x={0} y={top - 4} fontSize={11} fill={ink}>{format(max)}</text>
        <line x1={0} x2={W} y1={H - bottom} y2={H - bottom} stroke={grid} />
        {points.map((p, i) => {
          const bh = p.value > 0 ? Math.max(2, (plot * p.value) / max) : 0;
          const x = i * slot + (slot - bar) / 2, y = H - bottom - bh, r = Math.min(4, bh, bar / 2);
          return (
            <g key={p.key} onMouseEnter={() => setHover(i)}>
              <rect x={i * slot} y={top} width={slot} height={plot} fill="transparent" />
              {bh > 0 && (
                <path d={`M${x},${H - bottom} V${y + r} Q${x},${y} ${x + r},${y} H${x + bar - r} Q${x + bar},${y} ${x + bar},${y + r} V${H - bottom} Z`}
                  fill={theme.palette.primary.main} opacity={hover == null || hover === i ? 1 : 0.45} />
              )}
              {i % every === 0 && (
                <text x={i * slot + slot / 2} y={H - 6} fontSize={11} fill={ink} textAnchor="middle">{p.label}</text>
              )}
            </g>
          );
        })}
      </svg>
      {h && (
        <Box sx={{ position: 'absolute', top: -4, left: `${Math.min(80, Math.max(0, ((hover! + 0.5) / n) * 100 - 10))}%`, px: 1, py: 0.5,
          bgcolor: 'background.paper', border: '1px solid', borderColor: 'divider', borderRadius: 1, boxShadow: 1, pointerEvents: 'none', whiteSpace: 'nowrap' }}>
          <Typography variant="caption" color="text.secondary" display="block">{h.tooltip ?? h.label}</Typography>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{format(h.value)}</Typography>
        </Box>
      )}
    </Box>
  );
}

/** A two-part split (e.g. female / male) as one thin bar with both values labelled. */
export function SplitBar({ parts }: { parts: { label: string; value: number }[] }) {
  const total = parts.reduce((n, p) => n + p.value, 0);
  const shades = ['primary.main', 'primary.light'];
  return (
    <Box>
      <Box sx={{ display: 'flex', height: 10, borderRadius: 1, overflow: 'hidden', bgcolor: 'action.hover', gap: total ? '2px' : 0 }}>
        {parts.map((p, i) => total > 0 && p.value > 0 && <Box key={p.label} sx={{ width: `${(100 * p.value) / total}%`, bgcolor: shades[i % 2] }} />)}
      </Box>
      <Stack direction="row" spacing={2} sx={{ mt: 0.75 }}>
        {parts.map((p, i) => (
          <Stack key={p.label} direction="row" spacing={0.75} alignItems="center">
            <Box sx={{ width: 10, height: 10, borderRadius: 0.5, bgcolor: shades[i % 2] }} />
            <Typography variant="body2" color="text.secondary">{p.label} <strong style={{ color: 'inherit' }}>{p.value.toLocaleString()}</strong>
              {total > 0 && ` (${Math.round((100 * p.value) / total)}%)`}</Typography>
          </Stack>
        ))}
      </Stack>
    </Box>
  );
}
