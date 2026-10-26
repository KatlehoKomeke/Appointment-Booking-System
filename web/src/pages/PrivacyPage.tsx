/**
 * The privacy notice that section 18 of the Protection of Personal Information Act (POPIA) asks for when personal
 * information is collected: who is responsible, what is collected and why, how long it is kept, and the customer's
 * rights.
 *
 * @packageDocumentation
 */
import { Card } from '../ui/Card';
import { PageHeading } from '../ui/PageHeading';
import styles from './PrivacyPage.module.css';

/** The notice's sections, in the order section 18 lists the facts. */
const SECTIONS = [
  {
    id: 'privacy-responsible',
    heading: 'Who is responsible',
    body: 'Fynbos Bank (fictional demo). Its information officer answers questions at privacy@fynbosbank.example.',
  },
  {
    id: 'privacy-collect',
    heading: 'What we collect',
    body: 'Your first name, last name and cellphone number. All three are needed to book, so none is optional. We never ask for your ID number. On the hosted demo, book with a demo number, 082 555 0000 to 082 555 0199, and a made-up name; other visitors cannot see your booking.',
  },
  {
    id: 'privacy-why',
    heading: 'Why',
    body: 'To schedule, confirm and manage this appointment, and for nothing else. We never use your details for marketing.',
  },
  {
    id: 'privacy-retention',
    heading: 'How long we keep it',
    body: "The hosted demo deletes every booking, with your name and number, from its database about 48 hours after it is made. The database's backups, which could still hold it, expire after 7 days. A copy run locally keeps them on that machine until you delete them.",
  },
  {
    id: 'privacy-rights',
    heading: 'Your rights',
    body: 'You may ask to see your details or to have them corrected. If you are unhappy with how they are handled, you may complain to the Information Regulator at inforegulator.org.za.',
  },
] as const;

/** The privacy notice, at `/privacy`. */
export function PrivacyPage() {
  return (
    <>
      <PageHeading
        title="Privacy notice"
        lead="What BranchBook collects when you book, and why, as section 18 of the Protection of Personal Information Act (POPIA) requires."
      />
      <div className={styles.sections}>
        {SECTIONS.map((section) => (
          <Card key={section.id} as="section" aria-labelledby={section.id}>
            <h2 id={section.id} className={styles.heading}>
              {section.heading}
            </h2>
            <p className={styles.body}>{section.body}</p>
          </Card>
        ))}
      </div>
    </>
  );
}
