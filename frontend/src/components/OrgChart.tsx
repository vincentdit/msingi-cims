import ZoomInIcon from '@mui/icons-material/ZoomIn';
import ZoomOutIcon from '@mui/icons-material/ZoomOut';
import { Box, Chip, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import { useState } from 'react';
import type { OrgPosition, OrgUnit } from '../api/types';

export const UNIT_KIND: Record<string, { label: string; colour: string }> = {
  BOARD: { label: 'Board', colour: '#0F1B2D' },
  COMMITTEE: { label: 'Committee', colour: '#5E35B1' },
  OFFICE: { label: 'Office', colour: '#1E4E8C' },
  DIRECTORATE: { label: 'Directorate', colour: '#00695C' },
  FACULTY: { label: 'Faculty', colour: '#AD6800' },
  DEPARTMENT: { label: 'Department', colour: '#2E7D32' },
  UNIT: { label: 'Unit', colour: '#455A64' },
  SECTION: { label: 'Section', colour: '#6D4C41' },
  CAMPUS: { label: 'Campus', colour: '#C62828' },
};

export function holderText(p: OrgPosition) {
  if (!p.holder) return 'Vacant';
  return p.acting ? `${p.holder} (acting)` : p.holder;
}

/** The units under a unit, line units first, the advisory ones (committees beside it) apart. */
const split = (u: OrgUnit) => ({ line: u.children.filter((c) => !c.advisory), advisory: u.children.filter((c) => c.advisory) });

function UnitBox({ u, selected, onSelect }: { u: OrgUnit; selected?: number | null; onSelect?: (u: OrgUnit) => void }) {
  const k = UNIT_KIND[u.kind] ?? UNIT_KIND.UNIT;
  const head = u.positions.find((p) => p.head) ?? u.positions[0];
  const others = u.positions.length - (head ? 1 : 0);
  return (
    <Box role={onSelect ? 'button' : undefined} tabIndex={onSelect ? 0 : undefined} onClick={() => onSelect?.(u)}
      onKeyDown={(e) => { if (onSelect && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); onSelect(u); } }}
      sx={{ display: 'inline-block', minWidth: 150, maxWidth: 210, px: 1.25, py: 1, bgcolor: 'background.paper', borderRadius: 1.5, textAlign: 'center',
        border: 2, borderStyle: u.advisory ? 'dashed' : 'solid', borderColor: selected === u.id ? 'secondary.main' : k.colour,
        boxShadow: selected === u.id ? 4 : 1, cursor: onSelect ? 'pointer' : 'default', verticalAlign: 'top',
        '&:hover': onSelect ? { boxShadow: 4 } : undefined, '&:focus-visible': { outline: '3px solid', outlineColor: 'secondary.main' } }}>
      <Typography sx={{ fontSize: 10, letterSpacing: 0.6, textTransform: 'uppercase', color: k.colour, fontWeight: 700 }}>{k.label}</Typography>
      <Typography sx={{ fontWeight: 700, fontSize: 13.5, lineHeight: 1.25 }}>{u.name}</Typography>
      {head && (
        <Box sx={{ mt: 0.5 }}>
          <Typography sx={{ fontSize: 12, color: 'text.secondary', lineHeight: 1.2 }}>{head.title}</Typography>
          <Typography sx={{ fontSize: 12.5, fontWeight: 600, lineHeight: 1.25, color: head.holder ? 'text.primary' : 'warning.main' }}>{holderText(head)}</Typography>
        </Box>
      )}
      {others > 0 && <Typography sx={{ fontSize: 11, color: 'text.secondary', mt: 0.25 }}>{`+${others} more`}</Typography>}
    </Box>
  );
}

const LINE = '2px solid';

/** One unit and everything under it, drawn as a classic top-down organisation chart (lines join a unit to the units under it). */
function Branch({ u, selected, onSelect }: { u: OrgUnit; selected?: number | null; onSelect?: (u: OrgUnit) => void }) {
  const { line, advisory } = split(u);
  return (
    <Box component="li" sx={{ listStyle: 'none', position: 'relative', textAlign: 'center', px: 0.75, pt: '22px',
      // the horizontal line to the siblings, and the drop to this unit
      '&::before, &::after': { content: '""', position: 'absolute', top: 0, right: '50%', width: '50%', height: '22px', borderTop: LINE, borderColor: 'divider' },
      '&::after': { right: 'auto', left: '50%', borderLeft: LINE, borderLeftColor: 'divider' },
      '&:only-child::before, &:only-child::after': { display: 'none' },
      '&:only-child': { pt: 0 },
      '&:first-of-type::before, &:last-of-type::after': { border: 0 },
      '&:last-of-type::before': { borderRight: LINE, borderRightColor: 'divider', borderTopRightRadius: 6 },
      '&:first-of-type::after': { borderTopLeftRadius: 6 },
    }}>
      <Stack direction="row" spacing={1.5} justifyContent="center" alignItems="center">
        {/* an invisible copy of the advisory column on the left keeps the unit itself centred over the units under it */}
        {advisory.length > 0 && (
          <Stack spacing={1} aria-hidden sx={{ visibility: 'hidden', pr: 1.5 }}>
            {advisory.map((a) => <UnitBox key={a.id} u={a} />)}
          </Stack>
        )}
        <UnitBox u={u} selected={selected} onSelect={onSelect} />
        {advisory.length > 0 && (
          <Stack spacing={1} sx={{ position: 'relative', pl: 1.5, borderLeft: '2px dashed', borderColor: 'divider' }}>
            {advisory.map((a) => <UnitBox key={a.id} u={a} selected={selected} onSelect={onSelect} />)}
          </Stack>
        )}
      </Stack>
      {line.length > 0 && (
        <Box component="ul" sx={{ position: 'relative', display: 'flex', justifyContent: 'center', p: 0, pt: '22px', m: 0,
          '&::before': { content: '""', position: 'absolute', top: 0, left: '50%', height: '22px', borderLeft: LINE, borderColor: 'divider' } }}>
          {line.map((c) => <Branch key={c.id} u={c} selected={selected} onSelect={onSelect} />)}
        </Box>
      )}
    </Box>
  );
}

/** The organisation chart: scrolls sideways when it is wider than the screen, and can be zoomed out to see it whole. */
export function OrgChart({ roots, selected, onSelect }: { roots: OrgUnit[]; selected?: number | null; onSelect?: (u: OrgUnit) => void }) {
  const [zoom, setZoom] = useState(1);
  return (
    <Box sx={{ position: 'relative' }}>
      <Stack direction="row" spacing={0.5} sx={{ position: 'absolute', right: 8, top: 8, zIndex: 1, bgcolor: 'background.paper', borderRadius: 1, boxShadow: 1,
        '@media print': { display: 'none' } }}>
        <Tooltip title="Zoom out"><span><IconButton size="small" disabled={zoom <= 0.4} onClick={() => setZoom((z) => Math.round((z - 0.15) * 100) / 100)}><ZoomOutIcon fontSize="small" /></IconButton></span></Tooltip>
        <Chip size="small" label={`${Math.round(zoom * 100)}%`} onClick={() => setZoom(1)} sx={{ alignSelf: 'center' }} />
        <Tooltip title="Zoom in"><span><IconButton size="small" disabled={zoom >= 1.3} onClick={() => setZoom((z) => Math.round((z + 0.15) * 100) / 100)}><ZoomInIcon fontSize="small" /></IconButton></span></Tooltip>
      </Stack>
      <Box sx={{ overflow: 'auto', py: 5, px: 2, bgcolor: 'action.hover', borderRadius: 2, '@media print': { overflow: 'visible', bgcolor: 'transparent' } }}>
        <Box sx={{ zoom, width: 'max-content', minWidth: '100%', mx: 'auto' }}>
          <Box component="ul" sx={{ display: 'flex', justifyContent: 'center', p: 0, m: 0 }}>
            {roots.map((r) => <Branch key={r.id} u={r} selected={selected} onSelect={onSelect} />)}
          </Box>
        </Box>
      </Box>
    </Box>
  );
}

/** The same structure as an indented list: easier on a phone, and for reading every position. */
export function OrgList({ roots, selected, onSelect, depth = 0 }: { roots: OrgUnit[]; selected?: number | null; onSelect?: (u: OrgUnit) => void; depth?: number }) {
  return (
    <Stack spacing={0.75}>
      {roots.map((u) => {
        const k = UNIT_KIND[u.kind] ?? UNIT_KIND.UNIT;
        return (
          <Box key={u.id}>
            <Box onClick={() => onSelect?.(u)} sx={{ ml: depth * 2.5, pl: 1.5, py: 0.75, borderLeft: 4, borderLeftColor: k.colour, borderRadius: 0.5,
              bgcolor: selected === u.id ? 'action.selected' : 'background.paper', cursor: onSelect ? 'pointer' : 'default', '&:hover': onSelect ? { bgcolor: 'action.hover' } : undefined }}>
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Typography sx={{ fontWeight: 700 }}>{u.name}</Typography>
                <Chip size="small" variant="outlined" label={k.label} sx={{ borderColor: k.colour, color: k.colour, height: 20 }} />
                {u.advisory && <Chip size="small" label="Advisory" sx={{ height: 20 }} />}
              </Stack>
              {u.positions.map((p) => (
                <Typography key={p.id} variant="body2" color="text.secondary">
                  {p.title}: <Box component="span" sx={{ color: p.holder ? 'text.primary' : 'warning.main', fontWeight: 600 }}>{holderText(p)}</Box>
                </Typography>
              ))}
            </Box>
            {u.children.length > 0 && <Box sx={{ mt: 0.75 }}><OrgList roots={u.children} selected={selected} onSelect={onSelect} depth={depth + 1} /></Box>}
          </Box>
        );
      })}
    </Stack>
  );
}
