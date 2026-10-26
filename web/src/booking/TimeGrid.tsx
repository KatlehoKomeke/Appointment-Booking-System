/**
 * One day's times as native radio buttons, in Morning and Afternoon groups, four to a row.
 *
 * Native radios give one tab stop, the arrow keys and the "checked" announcement with no keyboard code to write or
 * test. Each radio is hidden inside its tile, and the tile draws the state in words as well as colour: "Full", "1
 * left" and "Selected".
 *
 * @packageDocumentation
 */
import type { ReactNode } from 'react';

import type { Slot } from '../api/queries';
import { formatDateNoYear, formatTime, isMorning, type IsoDate } from '../lib/time';
import { cx } from '../ui/cx';
import styles from './TimeGrid.module.css';

/** Props for {@link TimeGrid}. */
export interface TimeGridProps {
  /** The day the times are on, `YYYY-MM-DD`, which the legend names. */
  date: IsoDate;
  /** The day's slots, full ones included (`seatsAvailable` 0). */
  slots: readonly Slot[];
  /** The chosen start, `HH:MM`, if any. */
  selected: string | undefined;
  /** Called with the start, `HH:MM`, of the time the customer chooses. */
  onSelect: (time: string) => void;
  /** Shown under the legend, before the times: when they were read, and Refresh. */
  children?: ReactNode;
}

interface TileProps {
  slot: Slot;
  time: string;
  checked: boolean;
  onSelect: (time: string) => void;
}

// The radio is named in full, "08:45, 1 left", rather than from the tile's text, which runs the time into its note
// for some screen readers ("08:451 left"); "Selected" is left out, since a screen reader already says "checked".
// The tile's visible words are hidden from screen readers, so browse mode does not read each time twice, and never
// reads "Selected" at all.
function Tile({ slot, time, checked, onSelect }: TileProps) {
  const full = slot.seatsAvailable <= 0;
  const lastSeat = slot.seatsAvailable === 1;
  let name = time;
  if (full) {
    name = `${time}, Full`;
  } else if (lastSeat) {
    name = `${time}, 1 left`;
  }
  let note: string | undefined;
  if (checked) {
    note = 'Selected';
  } else if (full) {
    note = 'Full';
  } else if (lastSeat) {
    note = '1 left';
  }
  return (
    <label
      className={cx(
        styles.tile,
        full && styles.full,
        lastSeat && styles.lastSeat,
        checked && styles.selected,
      )}
    >
      <input
        type="radio"
        name="slot"
        value={time}
        checked={checked}
        disabled={full}
        aria-label={name}
        className={cx(styles.radio, 'visually-hidden')}
        onChange={() => {
          onSelect(time);
        }}
      />
      <span className={cx(styles.time, 'tabular')} aria-hidden="true">
        {time}
      </span>
      {note !== undefined && (
        <span className={styles.note} aria-hidden="true">
          {note}
        </span>
      )}
    </label>
  );
}

function Group({
  label,
  slots,
  selected,
  onSelect,
}: {
  label: string;
  slots: readonly Slot[];
  selected: string | undefined;
  onSelect: (time: string) => void;
}) {
  if (slots.length === 0) {
    return null;
  }
  return (
    <fieldset className={styles.group}>
      <legend className={styles.groupLegend}>{label}</legend>
      <div className={styles.tiles}>
        {slots.map((slot) => {
          const time = formatTime(slot.startsAt);
          return (
            <Tile
              key={slot.startsAt}
              slot={slot}
              time={time}
              checked={time === selected && slot.seatsAvailable > 0}
              onSelect={onSelect}
            />
          );
        })}
      </div>
    </fieldset>
  );
}

/**
 * The time grid: a `fieldset` whose legend names the day and the zone, holding the morning and afternoon times.
 * Times are shown in SAST whatever zone the device is in.
 */
export function TimeGrid({ date, slots, selected, onSelect, children }: TimeGridProps) {
  const morning = slots.filter((slot) => isMorning(slot.startsAt));
  const afternoon = slots.filter((slot) => !isMorning(slot.startsAt));
  return (
    <fieldset className={styles.grid}>
      <legend className={styles.legend}>
        <h2 className={styles.title}>Available times on {formatDateNoYear(date)}</h2>
        <span className={styles.zone}>In South African Standard Time (SAST)</span>
      </legend>
      {children}
      <Group label="Morning" slots={morning} selected={selected} onSelect={onSelect} />
      <Group label="Afternoon" slots={afternoon} selected={selected} onSelect={onSelect} />
    </fieldset>
  );
}
