/*
 * This software Copyright by the RPTools.net development team, and
 * licensed under the Affero GPL Version 3 or, at your option, any later
 * version.
 *
 * MapTool Source Code is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public
 * License * along with this source Code.  If not, please visit
 * <http://www.gnu.org/licenses/> and specifically the Affero license
 * text at <http://www.gnu.org/licenses/agpl.html>.
 */
package net.rptools.maptool.model.notebook;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.rptools.maptool.model.GUID;
import net.rptools.maptool.model.Zone;

/**
 * {@code MapBookmarkManager} class is used to manage all the {@link NoteBookEntry}s in a campaign.
 * This class is thread safe so may be used from multiple threads.
 *
 * @implNote Maintaining the thread safety of this class depends on keeping to the following
 *     conventions when changing this class.
 *     <ul>
 *       <li>Whenever {@link #removedZones}, {@link #zoneEntries}, {@link #referenceEntries}, or
 *           {@link #idEntryMap} are modified then a {@link #writeLock} must be obtained.
 *       <li>Whenever {@link #removedZones}, {@link #zoneEntries}, {@link #referenceEntries}, or
 *           {@link #idEntryMap} are read then a {@link #readLock} must be obtained.
 *     </ul>
 *     Also do <strong>not</strong> fire the property change events while holding a lock.
 */
public final class NoteBook {

  /**
   * Value used for "no zone".
   *
   * @implNote This does not need to be the same every run as we never persist it.
   */
  private static final GUID NO_ZONE_ID = new GUID();

  /**
   * Name of event fired when a zone is removed. For this event
   *
   * <ul>
   *   <li>{@code oldValue} = the id of the zone that was removed.
   *   <li>{@code newValue} = null.
   * </ul>
   */
  public static final String ZONE_REMOVED_EVENT = "Zone Removed";

  /**
   * Name of event fired when a entries are added. For this event
   *
   * <ul>
   *   <li>{@code oldValue} = {@code Set<NoteBookEntry>} containing values that were replaced}.
   *   <li>{@code newValue} = {@code Set<NoteBookEntry>} containing the added values.
   * </ul>
   */
  public static final String ENTRIES_ADDED_EVENT = "Entries Added";

  /**
   * Name of event fired when a entries are removed. For this event
   *
   * <ul>
   *   <li>{@code oldValue} = {@code Set<NoteBookEntry>} containing the removed values.
   *   <li>{@code newValue} = null.
   * </ul>
   */
  public static final String ENTRIES_REMOVED_EVENT = "Entries Removed";

  /** The {@link NoteBookEntry}s for each {@link Zone} */
  private final Map<GUID, Map<UUID, NoteBookEntry>> zoneEntries = new HashMap<>();

  /**
   * The list of {@link Zone}s that have been removed so that we don't end up adding {@link
   * NoteBookEntry}s for {@link Zone}s that have been removed.
   */
  private final Set<GUID> removedZones = new HashSet<>();

  /** Mapping of {@link NoteBookEntry} references to {@link NoteBookEntry}s. */
  private final Map<String, Set<NoteBookEntry>> referenceEntries = new HashMap<>();

  /** Mapping between id and {@link NoteBookEntry}. */
  private final Map<UUID, NoteBookEntry> idEntryMap = new HashMap<>();

  /** Provides property change support for the {@code NoteBook}. */
  private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);

  /** Lock used to ensure consistency of all collections. */
  private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

  /**
   * The read lock for the collections, you <strong>must</strong> use this lock whenever you are
   * reading the values from {@link #removedZones} or {@link #zoneEntries}.
   */
  private final Lock readLock = readWriteLock.readLock();

  /**
   * The write lock for the collections, you <strong>must</strong> use this lock whenever you are
   * modifying the contents for {@link #removedZones}. You shouldn't use this lock if you are only
   * modifying {@link #zoneEntries}.
   *
   * @see #readLock
   */
  private final Lock writeLock = readWriteLock.writeLock();
  /**
   * Adds or replaces a {@link NoteBookEntry} to the note book being managed. This will replace
   * <strong>any</strong> {@link NoteBookEntry} with the same id.
   *
   * @see NoteBookEntry#getId()
   * @param entry the {@link NoteBookEntry} to add or replace.
   */
  public void putEntry(NoteBookEntry entry) {
    putEntry(entry, true);
  }

  /**
   * Adds or replaces a {@link NoteBookEntry} to the note book being managed. This will replace
   * <strong>any</strong> {@link NoteBookEntry} with the same id. This version of the function
   * allows you to specify if the listeners should be notified of the change. If you pass {@code
   * false} to this you are expected to perform the notification, this is to support bulk updates.
   *
   * @see NoteBookEntry#getId()
   * @param entry the {@link NoteBookEntry} to add or replace.
   * @param firePropertyChange {@code true} if listeners should be notified.
   */
  private void putEntry(NoteBookEntry entry, boolean firePropertyChange) {
    NoteBookEntry oldEntry = idEntryMap.get(entry.getId());
    writeLock.lock();
    try {
      /*
       * If the zone is in the list of zone that have been removed then drop the bookmark silently.
       * This is to avoid storing note book for zones that have already been removed.
       */
      GUID zoneId = entry.getZoneId().orElse(NO_ZONE_ID);
      if (removedZones.contains(zoneId)) {
        return;
      }

      /*
       * Check to see if we are replacing an entry and if so remove it from the other maps, as
       * the reference or the zone may have changed.
       */
      if (idEntryMap.containsKey(entry.getId())) {
        removeEntry(entry, false);
      }

      idEntryMap.put(entry.getId(), entry);
      if (entry.getReference().isPresent()) {
        String ref = entry.getReference().get();
        referenceEntries.putIfAbsent(ref, new HashSet<>());
        referenceEntries.get(ref).add(entry);
      }
      zoneEntries.putIfAbsent(zoneId, new HashMap<>());
      zoneEntries.get(zoneId).put(entry.getId(), entry);
    } finally {
      writeLock.unlock();
    }

    if (firePropertyChange) {
      Set<NoteBookEntry> removed = new HashSet<>();
      if (oldEntry != null) {
        removed.add(oldEntry);
      }
      fireChangeEvent(ENTRIES_ADDED_EVENT, removed, Set.of(entry));
    }
  }

  /**
   * Adds or replaces a {@link NoteBookEntry} to the note book being managed. This will replace
   * <strong>any</strong> {@link NoteBookEntry} with the same id.
   *
   * @see NoteBookEntry#getId()
   * @param notes the {@link NoteBookEntry}s to add or replace.
   */
  public void putEntries(Collection<NoteBookEntry> notes) {
    Set<NoteBookEntry> oldEntries = new HashSet<>();
    writeLock.lock(); // Want to lock the whole transaction
    try {
      for (NoteBookEntry note : notes) {
        if (idEntryMap.containsKey(note.getId())) {
          oldEntries.add(idEntryMap.get(note.getId()));
        }
        putEntry(note, false);
      }
    } finally {
      writeLock.unlock();
    }
    if (!notes.isEmpty()) {
      fireChangeEvent(ENTRIES_ADDED_EVENT, oldEntries, Set.copyOf(notes));
    }
  }

  /**
   * Returns the {@link NoteBookEntry}s being managed.
   *
   * @return the {@link NoteBookEntry}s being managed.
   */
  public Collection<NoteBookEntry> getEntries() {
    readLock.lock();
    try {
      return Collections.unmodifiableCollection(idEntryMap.values());
    } finally {
      readLock.unlock();
    }
  }

  /**
   * Returns the {@link NoteBookEntry}s for a {@link Zone}.
   *
   * @param zone the {@link Zone} to return the {@link NoteBookEntry}s for.
   * @return the {@link NoteBookEntry}s for a {@link Zone}.
   */
  public Collection<NoteBookEntry> getZoneEntries(Zone zone) {
    return getZoneEntries(zone.getId());
  }

  /**
   * Returns a {@link Map} of {@link Zone}s and the {@link NoteBookEntry}s for the them. Entries
   * with no zone are in the {@code Map} with the key of {@code null}.
   *
   * @return a {@link Map} of {@link Zone}s and the {@link NoteBookEntry}s for the them.
   */
  public Map<GUID, Set<NoteBookEntry>> getEntriesByZone() {
    readLock.lock();
    Map<GUID, Set<NoteBookEntry>> entries = new HashMap<>();
    try {
      for (GUID zoneId : zoneEntries.keySet()) {
        Set<NoteBookEntry> zEntries = new HashSet<>(zoneEntries.get(zoneId).values());
        if (zoneId.equals(NO_ZONE_ID)) {
          entries.put(null, zEntries);
        } else {
          entries.put(zoneId, zEntries);
        }
      }
    } finally {
      readLock.unlock();
    }

    return entries;
  }

  /**
   * Returns the {@link NoteBookEntry}s for a {@link Zone}.
   *
   * @param zoneId the id of the {@link Zone} to return the {@link NoteBookEntry}s.
   * @return the {@link NoteBookEntry}s for a {@link Zone}.
   */
  public Collection<NoteBookEntry> getZoneEntries(GUID zoneId) {
    Collection<NoteBookEntry> notes = null;
    readLock.lock();
    try {
      if (zoneEntries.containsKey(zoneId)) {
        notes = zoneEntries.get(zoneId).values();
      } else {
        notes = Collections.emptySet();
      }
    } finally {
      readLock.unlock();
    }

    return notes;
  }

  /**
   * Returns the {@link NoteBookEntry}s not attached to any {@link Zone}.
   *
   * @return the {@link NoteBookEntry}s not attached to any {@link Zone}.
   */
  public Collection<NoteBookEntry> getZoneLessEntries() {
    return getZoneEntries(NO_ZONE_ID);
  }

  /**
   * Called when a {@link Zone} is removed from the campaign as we are no longer interested in any
   * of the notes associated with it at this point.
   *
   * @param zoneId the id of the {@link Zone} that has been removed.
   */
  public void zoneRemoved(GUID zoneId) {
    writeLock.lock();
    try {

      // Make sure that entries are removed from the non zone collection when zone is removed
      if (zoneEntries.containsKey(zoneId)) {
        Set<NoteBookEntry> entries = new HashSet<>();
        entries.addAll(zoneEntries.get(zoneId).values());
        for (NoteBookEntry entry : entries) {
          removeEntry(entry, false);
        }
      }

      /*
       * add this to the list of Zones that have been removed as we do not want to add
       * any notes for Zones that have been removed, which could happen due to the perils
       * of multithreading.
       */
      removedZones.add(zoneId);
      zoneEntries.remove(zoneId);
    } finally {
      writeLock.unlock();
    }

    fireChangeEvent(ZONE_REMOVED_EVENT, zoneId, null);
  }

  /**
   * Removes a {@link NoteBookEntry} from the note book. This version of the function allows you to
   * specify if the listeners should be notified of the change. If you pass {@code false} to this
   * you are expected to perform the notification, this is to support bulk updates.
   *
   * @param entry The {@link NoteBookEntry} to remove.
   * @param firePropertyChange {@code true} if listeners should be notified.
   */
  private void removeEntry(NoteBookEntry entry, boolean firePropertyChange) {
    writeLock.lock();
    try {
      if (entry.getReference().isPresent()) {
        String ref = entry.getReference().get();
        Set<NoteBookEntry> entries = referenceEntries.get(ref);
        entries.remove(entry);
        if (entries.size() == 0) {
          referenceEntries.remove(ref);
        }
      }
      if (entry.getZoneId().isPresent()) {
        GUID oldZoneId = entry.getZoneId().get();
        zoneEntries.get(oldZoneId).remove(entry.getId());
      }
    } finally {
      writeLock.unlock();
    }

    if (firePropertyChange) {
      fireChangeEvent(ENTRIES_REMOVED_EVENT, Set.of(entry), null);
    }
  }

  /**
   * Removes a {@link NoteBookEntry} from the note book.
   *
   * @param entry The {@link NoteBookEntry} to remove.
   */
  public void removeEntry(NoteBookEntry entry) {
    removeEntry(entry, true);
  }

  /**
   * Returns all {@link NoteBookEntry}s contained in the {@code NoteBook} that have this reference
   * id.
   *
   * @param ref the reference id to search for.
   * @return all {@link NoteBookEntry}s contained in the {@code NoteBook} that have this reference
   */
  public Collection<NoteBookEntry> getByReference(String ref) {
    Set<NoteBookEntry> entries = new HashSet<>();

    readLock.lock();
    try {
      if (referenceEntries.containsKey(ref)) {
        entries.addAll(referenceEntries.get(ref));
      }
    } finally {
      readLock.unlock();
    }
    return entries;
  }

  /**
   * Returns the {@link NoteBookEntry} for a certain id.
   *
   * @param id the id of the {@link NoteBookEntry} to retrieve.
   * @return the {@link NoteBookEntry} for the id.
   */
  public Optional<NoteBookEntry> getById(UUID id) {
    readLock.lock();

    try {
      if (idEntryMap.containsKey(id)) {
        return Optional.of(idEntryMap.get(id));
      } else {
        return Optional.empty();
      }
    } finally {
      readLock.unlock();
    }
  }

  /**
   * Called when a {@link Zone} is added to the campaign.
   *
   * @param zoneId the id of the {@link Zone} that was added to the campaign.
   * @implNote {@code MapBookmarkManager} doesn't really care if a <strong>new</strong> {@link Zone}
   *     is added to the campaign but if it is a {@link Zone} that has previously been removed and
   *     is now being re-added then we need to make sure that the {@link Zone} is removed from
   *     {@link #removedZones} so new {@link NoteBookEntry}s can be added. There is no need to fire
   *     off a property change event as the changes are not visible to anyone.
   */
  public void zoneAdded(GUID zoneId) {
    writeLock.lock();
    try {
      /*
       * This is to deal with the unlikely case where a zone is removed and readded so we can
       * continue adding new notes.
       */
      removedZones.remove(zoneId);
    } finally {
      writeLock.unlock();
    }
  }

  /** Notify {@link PropertyChangeListener}s that the {@code NoteBook} has been changed. */
  private void fireChangeEvent(String eventName, Object oldValue, Object newValue) {
    assert !readWriteLock.isWriteLockedByCurrentThread()
        : "Cannot fire property change events while holding lock";
    propertyChangeSupport.firePropertyChange(eventName, oldValue, newValue);
  }

  /**
   * Adds a {@link PropertyChangeListener} to listen for changes to the {@code NoteBook}.
   *
   * @param propertyChangeListener the {@link PropertyChangeListener} to listen for changes.
   */
  public void addPropertyChangeListener(PropertyChangeListener propertyChangeListener) {
    // No need for locking a PropertyChangeSupport is thread safe.
    propertyChangeSupport.addPropertyChangeListener(propertyChangeListener);
  }

  /**
   * Removes a {@link PropertyChangeListener} from those listening for changes to the {@code
   * NoteBook}.
   *
   * @param propertyChangeListener the {@link PropertyChangeListener} to remove.
   */
  public void removePropertyChangeListener(PropertyChangeListener propertyChangeListener) {
    // No need for locking a PropertyChangeSupport is thread safe.
    propertyChangeSupport.removePropertyChangeListener(propertyChangeListener);
  }
}
