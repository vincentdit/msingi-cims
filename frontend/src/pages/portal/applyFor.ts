/** The programme a visitor chose on a programme page, kept for this browser tab until they reach the programme choices. */
export interface ApplyFor {
  code: string;
  windowProgrammeId: number;
  windowId: number;
}

const KEY = 'cims.applyFor';

export function rememberChoice(c: ApplyFor) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(c));
  } catch {
    /* storage unavailable: the applicant picks the programme themselves */
  }
}

export function chosenProgramme(): ApplyFor | null {
  try {
    const v = sessionStorage.getItem(KEY);
    return v ? (JSON.parse(v) as ApplyFor) : null;
  } catch {
    return null;
  }
}

export function forgetChoice() {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    /* nothing to forget */
  }
}
