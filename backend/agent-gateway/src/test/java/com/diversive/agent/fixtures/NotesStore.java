package com.diversive.agent.fixtures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The notes domain's data, in memory, for the gateway's own tests: what resolvers search, checks and counts
 * read, and handlers change. "travel" matches two folders, "receipts" is an empty folder, "shopping" matches
 * two notes, and note 13 is archived. A new store starts from exactly this.
 */
public final class NotesStore {

    public final Map<Long, String> folders = new TreeMap<>(Map.of(7L, "Recipes", 8L, "Travel plans", 9L, "Travel receipts"));
    public final Map<Long, Long> notesInFolder = new HashMap<>(Map.of(7L, 4L, 8L, 2L, 9L, 0L));
    public final Map<Long, String> notes = new TreeMap<>(Map.of(11L, "Shopping list", 12L, "Packing list", 13L, "Old shopping list"));
    public final Set<Long> archived = new HashSet<>(Set.of(13L));
    public final Set<Long> pinned = new HashSet<>();
    /** Every share that ran: which folder, by which channel, how many notes. */
    public final List<String> shares = new ArrayList<>();
    private long nextNoteId = 100;

    public long share(long folderId, String channel) {
        long count = notesInFolder.get(folderId);
        shares.add("folder " + folderId + " by " + channel + ": " + count + " notes");
        return count;
    }

    public long copy(long noteId) {
        long copyId = nextNoteId++;
        notes.put(copyId, "Copy of " + notes.get(noteId));
        return copyId;
    }
}
