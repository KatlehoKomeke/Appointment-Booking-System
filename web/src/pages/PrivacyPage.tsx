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
    body: 'Fynbos Bank (fictional demo). Because it is fictional, it has no information officer to write to; Your rights, below, says what you can do yourself.',
  },
  {
    id: 'privacy-collect',
    heading: 'What we collect',
    body: "Your first name, last name and cellphone number. All three are needed to book, so none is optional. We never ask for your ID number. branchbook.click and stg.branchbook.click also log each request's IP address and browser in their firewall's log, to find and stop abuse, for 30 days and 7 days respectively; a copy run locally logs neither. Use only your own cellphone number: branchbook.click texts it. The demo numbers 082 555 0000 to 082 555 0199 are never texted, the test copy at stg.branchbook.click accepts only those, and other visitors cannot see your booking.",
  },
  {
    id: 'privacy-why',
    heading: 'Why',
    body: 'To schedule, confirm and manage this appointment, and for nothing else: we text your confirmation, and a cancellation if you cancel. We never use your details for marketing.',
  },
  {
    id: 'privacy-operator',
    heading: 'Who sends the SMS',
    body: 'On branchbook.click, AWS End User Messaging, in Cape Town, sends each SMS for us as our operator. It receives only your number and the message, and keeps its own record of each message under its service terms. At most five messages a day go to one number. To count them, the site keeps a keyed fingerprint of the number, never the number itself, for about a day after each message.',
  },
  {
    id: 'privacy-retention',
    heading: 'How long we keep it',
    body: "branchbook.click, and its test copy at stg.branchbook.click, keep a booking, with your name and number, until its appointment is over, and delete it within the hour after it ends. The database's backups, which could still hold it, expire after at most 14 days; if either site's database is ever deleted, a final copy of it is kept until we delete that too. A copy run locally keeps them on that machine until you delete them. A booking you cancel is deleted at once; only the cancellation SMS is kept, until it has been sent or has failed for good. For about a day after you book, a one-way fingerprint of the booking's details is kept, never the details themselves, so that the same booking sent twice is booked once.",
  },
  {
    id: 'privacy-rights',
    heading: 'Your rights',
    body: 'You may see your booking by opening it on Find your appointment with its reference and the last four digits of your number. Until it starts, you may cancel it, which deletes it at once; to correct a detail, cancel and book again. If you are unhappy with how your details are handled, you may complain to the Information Regulator at inforegulator.org.za.',
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
