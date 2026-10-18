import { Container } from '@mui/material';
import { useSession } from '../../auth/session';
import { PageTitle } from '../../components/common';
import HelpCentre from '../../components/helpdesk/HelpCentre';

/** The portal's help page: an applicant who is signed in sees their own requests; anyone else asks with a name and mobile number. */
export default function HelpPage() {
  const { user } = useSession();
  const applicant = !!user('applicant');
  return (
    <Container maxWidth="lg" sx={{ py: { xs: 3, md: 5 } }}>
      <PageTitle title="Help" subtitle={applicant ? 'Find an answer, or ask the institution and follow the reply here.'
        : 'Find an answer, or ask the institution for help. You do not need an account.'} />
      <HelpCentre mode={applicant ? 'applicant' : 'public'} />
    </Container>
  );
}
