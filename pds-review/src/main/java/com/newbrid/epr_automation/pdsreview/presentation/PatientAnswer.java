package com.newbrid.epr_automation.pdsreview.presentation;

/**
 * One thing the patient typed or picked on a triage screen — an entry from {@code user.data[]}.
 *
 * <p>These are <b>patient-stated and unverified</b>. The review screen has to say so (FR-REV-03):
 * everything a reviewer is comparing against PDS came from somebody filling in a form in a
 * waiting room, possibly on behalf of somebody else, and treating it as established fact is how
 * the wrong patient gets matched.
 *
 * @param question the question as it was put, which is the only handle on most fields
 * @param answer   what they gave; empty when the screen was left blank
 * @param key      a stable name, populated for almost nothing — {@code dob} alone in the samples
 * @param type     the input control: text, date, select, radio, tel, textarea
 * @param id       the client-generated field id, {@code field-<epoch-ms>}
 */
public record PatientAnswer(String question, String answer, String key, String type, String id) {

    public boolean answered() {
        return answer != null && !answer.isBlank();
    }

    /** Whether this answer carries the stable key {@code name}, ignoring case. */
    public boolean hasKey(String name) {
        return key != null && !key.isBlank() && key.equalsIgnoreCase(name);
    }
}
