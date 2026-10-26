/**
 * Step 4, Your details: the customer's first name, last name and South African cellphone number (mockup
 * docs/assets/ui/05-details.png).
 *
 * Each field is checked when it is left, by the same rules as the API (`detailsForm.ts`). The details are kept in the
 * tab's session storage, never the URL, so Back, Edit and a refresh keep them. When the server refuses a detail at
 * the review step, it sends the customer back here with its errors in the router's state, shown on their fields.
 *
 * @packageDocumentation
 */
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useLocation, useNavigate } from 'react-router';

import { Alert } from '../ui/Alert';
import { Button } from '../ui/Button';
import { Field } from '../ui/Field';
import { PageHeading } from '../ui/PageHeading';
import { BookingSoFar } from './BookingSoFar';
import { stepDocTitle, useBookingParams } from './bookingParams';
import { loadDetails, saveDetails } from './bookingSession';
import {
  type DetailsField,
  type DetailsFormValues,
  detailsSchema,
  readHandOff,
} from './detailsForm';
import styles from './DetailsStep.module.css';

const EMPTY: DetailsFormValues = { firstName: '', lastName: '', mobile: '' };

/** The details step. */
export function DetailsStep() {
  const { goToStep } = useBookingParams();
  const location = useLocation();
  const navigate = useNavigate();
  // Read once: the errors belong to the visit that brought them, and are cleared from history below.
  const [handOff] = useState(() => readHandOff(location.state));
  // Read once too: `useForm` keeps only its first defaults, and the form re-renders on every keystroke once touched.
  const [defaultValues] = useState(() => loadDetails() ?? EMPTY);

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<DetailsFormValues>({
    resolver: zodResolver(detailsSchema),
    mode: 'onTouched',
    defaultValues,
  });

  useEffect(() => {
    if (handOff === undefined) {
      return;
    }
    for (const [field, message] of Object.entries(handOff.serverErrors) as [
      DetailsField,
      string,
    ][]) {
      setError(field, { type: 'server', message });
    }
    // Drop the errors from the history entry, so coming Back to this step later does not show them again.
    void navigate({ search: location.search }, { replace: true, state: null });
  }, [handOff, setError, navigate, location.search]);

  const onValid = (values: DetailsFormValues) => {
    saveDetails(values);
    goToStep('review');
  };

  return (
    <div className={styles.step}>
      <PageHeading title="Your details" docTitle={stepDocTitle('details')} focusKey="details" />
      <BookingSoFar />
      {handOff !== undefined && (
        <Alert tone="error" title={handOff.message.title}>
          {handOff.message.body}
        </Alert>
      )}
      <form
        noValidate
        className={styles.form}
        onSubmit={(event) => void handleSubmit(onValid)(event)}
      >
        <Field
          id="details-first-name"
          label="First name"
          autoComplete="given-name"
          spellCheck={false}
          error={errors.firstName?.message}
          {...register('firstName')}
        />
        <Field
          id="details-last-name"
          label="Last name"
          autoComplete="family-name"
          spellCheck={false}
          error={errors.lastName?.message}
          {...register('lastName')}
        />
        <Field
          id="details-mobile"
          label="Cellphone number"
          hint="We SMS your confirmation to this number, for example 082 555 0101."
          type="tel"
          inputMode="tel"
          autoComplete="tel-national"
          error={errors.mobile?.message}
          {...register('mobile')}
        />
        <p className={styles.privacy}>
          Fynbos Bank (fictional demo) uses your name and number only to schedule, confirm and
          manage this appointment. We never ask for your ID number online.{' '}
          <Link to="/privacy">Privacy notice</Link>
        </p>
        <Button type="submit" fullWidth>
          Continue
        </Button>
      </form>
    </div>
  );
}
