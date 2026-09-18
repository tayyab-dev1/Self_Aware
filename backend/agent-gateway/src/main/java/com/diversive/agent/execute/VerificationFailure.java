package com.diversive.agent.execute;

/**
 * A handler ran but did not do what its capability declares: a fact it should publish is missing, or it
 * touched a different number of records than were checked and confirmed. Its transaction is rolled back.
 * Not bad luck: the count and the handler have drifted apart, and that is a bug worth paging someone over.
 */
final class VerificationFailure extends RuntimeException {

    VerificationFailure(String message) {
        super(message);
    }
}
