/**
 * Find your appointment, at `/manage`: the reference from the SMS and the last four digits of the cellphone
 * number it was booked with open the appointment's page.
 *
 * A wrong reference and wrong digits get the same answer, so the form cannot be used to find out which references
 * exist (BR-13). The wording comes from the problem's code, through `problemMessage`.
 *
 * @packageDocumentation
 */
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router';
import { z } from 'zod';

import { problemMessage } from '../api/problemCopy';
import { Alert } from '../ui/Alert';
import { Button } from '../ui/Button';
import { Field } from '../ui/Field';
import { PageHeading } from '../ui/PageHeading';
import { canonicalReference, last4Field, useLookupAppointment } from './ManagePage.lookup';
import styles from './ManagePage.module.css';

const lookupForm = z.object({
  reference: z
    .string()
    .trim()
    .min(1, { error: 'Enter the reference from your SMS.' })
    .refine((value) => canonicalReference(value) !== undefined, {
      error: 'Check the reference. It looks like APT-7K3M-9Q2X.',
    }),
  last4: last4Field,
});

/** The manage page, at `/manage`. */
export function ManagePage() {
  const navigate = useNavigate();
  const lookup = useLookupAppointment();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(lookupForm),
    mode: 'onTouched',
    defaultValues: { reference: '', last4: '' },
  });

  const onSubmit = handleSubmit(({ reference, last4 }) => {
    lookup.mutate(
      { reference: canonicalReference(reference) ?? reference, last4 },
      {
        onSuccess: (appointment) => {
          void navigate(`/appointments/${appointment.reference}`);
        },
      },
    );
  });

  const failure = lookup.isError ? problemMessage(lookup.error) : undefined;

  return (
    <div className={styles.page}>
      <PageHeading
        title="Find your appointment"
        lead="Enter the reference from your SMS and the last four digits of the cellphone number you booked with."
      />

      {failure !== undefined && (
        <Alert tone="error">
          <strong>{failure.title}</strong> {failure.body}
        </Alert>
      )}

      <form
        className={styles.form}
        noValidate
        onSubmit={(event) => {
          void onSubmit(event);
        }}
      >
        <Field
          id="reference"
          label="Reference"
          hint="It starts with APT. Hyphens are optional."
          className={styles.reference}
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          error={errors.reference?.message}
          {...register('reference')}
        />
        <Field
          id="last4"
          label="Last four digits of your cellphone number"
          className={styles.digits}
          inputMode="numeric"
          autoComplete="off"
          maxLength={4}
          error={errors.last4?.message}
          {...register('last4')}
        />
        <Button type="submit" fullWidth disabled={lookup.isPending}>
          Find appointment
        </Button>
      </form>

      <p className={styles.note}>
        Can&apos;t find your SMS? This demo sends no real messages: yours is in the{' '}
        <Link to="/messages">simulated inbox</Link>.
      </p>
      <p className={styles.note}>
        After 5 wrong tries within an hour, lookups for that reference pause for up to an hour, even
        with the right digits.
      </p>
    </div>
  );
}
