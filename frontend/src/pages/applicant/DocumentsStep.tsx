import UploadIcon from '@mui/icons-material/UploadFile';
import { Alert, Box, Button, Chip, Stack, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { api, privateImageUrl, uploadForm } from '../../api/client';
import { ErrorAlert } from '../../components/common';

export interface AppDocument {
  typeId: number; code: string; name: string; description?: string; required: boolean; maxKb: number;
  documentId?: number; fileName?: string; contentType?: string; sizeBytes?: number; status?: 'UPLOADED' | 'ACCEPTED' | 'REJECTED';
  reviewNote?: string; reviewedByName?: string; reviewedAt?: string; uploadedAt?: string; canUpload: boolean;
}

export const sizeLabel = (kb: number) => (kb >= 1024 ? `${(kb / 1024).toFixed(kb % 1024 ? 1 : 0)} MB` : `${kb} KB`);

export function useAppDocuments(appId: number) {
  return useQuery({ queryKey: ['applicant', 'documents', appId], queryFn: () => api<AppDocument[]>(`/api/applicant/applications/${appId}/documents`, { realm: 'applicant' }) });
}

/** The documents the admission window asks for (FR-ADM-07): upload, view, replace. */
export function DocumentsStep({ appId, onNext }: { appId: number; onNext?: () => void }) {
  const { t } = useTranslation();
  const docs = useAppDocuments(appId);
  return (
    <Stack spacing={2}>
      <Typography color="text.secondary">{t('docs.intro')}</Typography>
      <ErrorAlert error={docs.error} />
      {(docs.data ?? []).map((d) => <DocumentRow key={d.typeId} appId={appId} d={d} />)}
      {onNext && <Box><Button variant="contained" onClick={onNext}>{t('docs.next')}</Button></Box>}
    </Stack>
  );
}

function DocumentRow({ appId, d }: { appId: number; d: AppDocument }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const refresh = () => { qc.invalidateQueries({ queryKey: ['applicant', 'documents', appId] }); qc.invalidateQueries({ queryKey: ['applicant', 'applications'] }); };
  const up = useMutation({
    mutationFn: (f: File) => { const form = new FormData(); form.append('file', f); return uploadForm(`/api/applicant/applications/${appId}/documents/${d.typeId}`, 'applicant', form); },
    onSuccess: refresh,
  });
  const rm = useMutation({ mutationFn: () => api(`/api/applicant/applications/${appId}/documents/${d.typeId}`, { realm: 'applicant', method: 'DELETE' }), onSuccess: refresh });
  const view = async () => {
    const w = window.open('', '_blank');
    const url = await privateImageUrl(`/api/applicant/applications/${appId}/documents/${d.typeId}/file`, 'applicant');
    if (w) w.location.href = url; else window.location.href = url;
  };
  return (
    <Box sx={{ p: 1.5, border: 1, borderColor: d.status === 'REJECTED' ? 'error.light' : 'divider', borderRadius: 2 }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography sx={{ fontWeight: 600 }}>{d.name}</Typography>
            <Chip size="small" variant="outlined" label={d.required ? t('docs.required') : t('docs.optional')} />
            {d.status && <Chip size="small" color={d.status === 'ACCEPTED' ? 'success' : d.status === 'REJECTED' ? 'error' : 'default'} label={t(`docs.status.${d.status}`)} />}
          </Stack>
          {d.description && <Typography variant="body2" color="text.secondary">{d.description}</Typography>}
          <Typography variant="caption" color="text.secondary">{d.fileName ?? t('docs.notUploaded')} · PDF, JPG, PNG {t('docs.maxSize', { size: sizeLabel(d.maxKb) })}</Typography>
        </Box>
        <Stack direction="row" spacing={1}>
          {d.documentId && <Button size="small" onClick={view}>{t('docs.view')}</Button>}
          {d.canUpload && (
            <Button size="small" variant={d.documentId ? 'outlined' : 'contained'} startIcon={<UploadIcon />} disabled={up.isPending} onClick={() => input.current?.click()}>
              {d.documentId ? t('docs.replace') : t('docs.upload')}
            </Button>
          )}
          {d.canUpload && d.documentId && d.status !== 'REJECTED' && <Button size="small" color="inherit" onClick={() => rm.mutate()}>{t('docs.remove')}</Button>}
        </Stack>
        <input ref={input} type="file" hidden accept="application/pdf,image/jpeg,image/png"
          onChange={(e) => { const f = e.target.files?.[0]; if (f) up.mutate(f); e.target.value = ''; }} />
      </Stack>
      {d.status === 'REJECTED' && <Alert severity="error" sx={{ mt: 1, py: 0 }}>{t('docs.notAccepted', { note: d.reviewNote })}</Alert>}
      <ErrorAlert error={up.error ?? rm.error} />
    </Box>
  );
}
