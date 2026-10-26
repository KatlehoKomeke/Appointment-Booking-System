import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useForm } from 'react-hook-form';
import { describe, expect, it } from 'vitest';

import { Field } from './Field';

describe('Field', () => {
  it('labels the input, and describes it by the hint and the error while there is one', () => {
    render(
      <Field
        id="mobile"
        label="Cellphone number"
        hint="We SMS your confirmation to this number, for example 082 555 0101."
        error="We need a cellphone number to SMS your confirmation."
        defaultValue="082 555 010"
      />,
    );

    const input = screen.getByRole('textbox', { name: 'Cellphone number' });
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription(
      'We SMS your confirmation to this number, for example 082 555 0101. We need a cellphone number to SMS your confirmation.',
    );
  });

  it('is not marked invalid without an error, and keeps a description the caller gives', () => {
    render(
      <>
        <p id="privacy">We never ask for your ID number.</p>
        <Field id="first" label="First name" aria-describedby="privacy" />
      </>,
    );

    const input = screen.getByRole('textbox', { name: 'First name' });
    expect(input).not.toHaveAttribute('aria-invalid');
    expect(input).toHaveAccessibleDescription('We never ask for your ID number.');
  });

  it("takes react-hook-form's register, ref included, so an invalid submit focuses the field", async () => {
    const user = userEvent.setup();

    function Form() {
      const {
        register,
        handleSubmit,
        formState: { errors },
      } = useForm<{ firstName: string }>({ defaultValues: { firstName: '' } });
      return (
        <form noValidate onSubmit={(event) => void handleSubmit(() => undefined)(event)}>
          <Field
            id="firstName"
            label="First name"
            error={errors.firstName?.message}
            {...register('firstName', { required: 'Enter your first name.' })}
          />
          <button type="submit">Continue</button>
        </form>
      );
    }

    render(<Form />);
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    const input = screen.getByRole('textbox', { name: 'First name' });
    expect(input).toHaveFocus();
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Enter your first name.');
  });
});
