/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.collections;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.annotation.concurrent.ThreadSafe;

/**
 * A set of objects that are indexed and thus can be queried by specific fields of the object.
 * Different {@link IndexedSet} instances may specify different fields to index. The field type must
 * be comparable. The field value must not be changed after an object is added to the set,
 * otherwise, behavior for all operations is not specified.
 *
 * If concurrent adds or removes for objects which are equivalent, but not the same exact object,
 * the behavior is undefined. Therefore, do not add or remove "clones" objects in the
 * {@link IndexedSet}.
 *
 * <p>
 * Example usage:
 *
 * We have a set of puppies:
 *
 * <pre>
 * class Puppy {
 *   private final String mName;
 *   private final long mId;
 *
 *   public Puppy(String name, long id) {
 *     mName = name;
 *     mId = id;
 *   }
 *
 *   public String name() {
 *     return mName;
 *   }
 *
 *   public long id() {
 *     return mId;
 *   }
 * }
 * </pre>
 *
 * We want to be able to retrieve the set of puppies via a puppy's id or name, one way is to have
 * two maps like {@code Map<String, Puppy> nameToPuppy} and {@code Map<Long, Puppy> idToPuppy},
 * another way is to use a single instance of {@link IndexedSet}!
 *
 * First, define the fields to be indexed:
 *
 * <pre>
 *  FieldIndex<Puppy> idIndex = new FieldIndex<Puppy> {
 *    {@literal @Override}
 *    Object getFieldValue(Puppy o) {
 *      return o.id();
 *    }
 *  }
 *
 *  FieldIndex<Puppy> nameIndex = new FieldIndex<Puppy> {
 *    {@literal @Override}
 *    Object getFieldValue(Puppy o) {
 *      return o.name();
 *    }
 *  }
 * </pre>
 *
 * Then create an {@link IndexedSet} and add puppies:
 *
 * <pre>
 * IndexedSet<Puppy> puppies = new IndexedSet<Puppy>(idIndex, nameIndex);
 * puppies.add(new Puppy("sweet", 0));
 * puppies.add(new Puppy("heart", 1));
 * </pre>
 *
 * Then retrieve the puppy named sweet:
 *
 * <pre>
 * Puppy sweet = puppies.getFirstByField(nameIndex, "sweet");
 * </pre>
 *
 * and retrieve the puppy with id 1:
 *
 * <pre>
 * Puppy heart = puppies.getFirstByField(idIndex, 1L);
 * </pre>
 *
 * @param <T> the type of object
 */
@ThreadSafe
public class IndexedSet<T> extends AbstractSet<T> {
  /** All objects in the set. This set is required to guarantee uniqueness of objects. */
  // TODO(gpang): remove this set, and just use the indexes.
  private final ConcurrentHashSet<T> mObjects = new ConcurrentHashSet<>(8, 0.95f, 8);

  /**
   * Map from {@link FieldIndex} to the index. An index is a map from index value to one or a set of
   * objects with that index value. A unique index is an index where each index value only maps to
   * one object. A non-unique index is an index where an index value can map to one or more objects.
   */

  private final Map<String, FieldIndex<T>> mIndices;

  /**
   * Constructs a new {@link IndexedSet} instance with at least one field as the index.
   *
   * @param field at least one field is needed to index the set of objects
   * @param otherFields other fields to index the set
   */
  @SafeVarargs
  public IndexedSet(IndexDefinition<T> field, IndexDefinition<T>... otherFields) {
    // count the numbers of two index types
    Iterable<IndexDefinition<T>> fields =
        Iterables.concat(Arrays.asList(field), Arrays.asList(otherFields));

    // initialization
    Map<String, FieldIndex<T>> indices = new HashMap<String, FieldIndex<T>>();

    for (IndexDefinition<T> indexDefinition : fields) {
      FieldIndex<T> index;
      if (indexDefinition.isUnique()) {
        index = new UniqueFieldIndex<T>(indexDefinition.getAbstracter());
      } else {
        index = new NonUniqueFieldIndex<T>(indexDefinition.getAbstracter());
      }

      if (indices.put(indexDefinition.getName(), index) != null) {
        throw new IllegalStateException("Adding two indices to indexedSet using same name.");
      }
    }

    mIndices = Collections.unmodifiableMap(indices);
  }

  /**
   * Removes all the entries in this set.
   *
   * This is an expensive operation, and concurrent adds are permitted.
   */
  public void clear() {
    for (T obj : mObjects) {
      remove(obj);
    }
  }

  /**
   * Adds an object o to the set if there is no other object o2 such that
   * {@code (o == null ? o2 == null : o.equals(o2))}. If this set already contains the object, the
   * call leaves the set unchanged.
   *
   * @param object the object to add
   * @return true if this set did not already contain the specified element
   */
  @Override
  public boolean add(T object) {
    Preconditions.checkNotNull(object);

    // Locking this object protects against removing the exact object, but does not protect against
    // removing a distinct, but equivalent object.
    synchronized (object) {
      if (!mObjects.addIfAbsent(object)) {
        // This object is already added, possibly by another concurrent thread.
        return false;
      }

      for (Map.Entry<String, FieldIndex<T>> fieldInfo : mIndices.entrySet()) {
        fieldInfo.getValue().put(object);
      }
    }
    return true;
  }

  /**
   * Returns an iterator over the elements in this set. The elements are returned in no particular
   * order. It is to implement {@link Iterable} so that users can foreach the {@link IndexedSet}
   * directly.
   *
   * Note that the behaviour of the iterator is unspecified if the underlying collection is modified
   * while a thread is going through the iterator.
   *
   * @return an iterator over the elements in this {@link IndexedSet}
   */
  @Override
  public Iterator<T> iterator() {
    return new IndexedSetIterator();
  }

  /**
   * Specialized iterator for {@link IndexedSet}.
   *
   * This is needed to support consistent removal from the set and the indices.
   */
  private class IndexedSetIterator implements Iterator<T> {
    private final Iterator<T> mSetIterator;
    private T mObject;

    public IndexedSetIterator() {
      mSetIterator = mObjects.iterator();
      mObject = null;
    }

    @Override
    public boolean hasNext() {
      return mSetIterator.hasNext();
    }

    @Override
    public T next() {
      final T next = mSetIterator.next();
      mObject = next;
      return next;
    }

    @Override
    public void remove() {
      if (mObject != null) {
        IndexedSet.this.remove(mObject);
        mObject = null;
      } else {
        throw new IllegalStateException("next() was not called before remove()");
      }
    }
  }

  /**
   * Whether there is an object with the specified unique index field value in the set.
   *
   * @param indexName the field index name
   * @param value the field value
   * @return true if there is one such object, otherwise false
   */
  public boolean contains(String indexName, Object value) {
    FieldIndex<T> index = mIndices.get(indexName);
    return index != null && index.contains(value);
  }

  /**
   * Gets a subset of objects with the specified field value. If there is no object with the
   * specified field value, a newly created empty set is returned. Otherwise, the returned set is
   * backed up by an internal set, so changes in internal set will be reflected in returned set.
   *
   * @param indexName the field index name
   * @param value the field value to be satisfied
   * @return the set of objects or an empty set if no such object exists
   */
  public Set<T> getByField(String indexName, Object value) {
    FieldIndex<T> index = mIndices.get(indexName);
    return index == null ? new HashSet<T>() : index.getByField(value);
  }

  /**
   * Gets the object from the set of objects with the specified non-unique field value.
   *
   * @param indexName the field index name
   * @param value the field value
   * @return the object or null if there is no such object
   */
  public T getFirstByField(String indexName, Object value) {
    FieldIndex<T> index = mIndices.get(indexName);
    return index == null ? null : index.getFirst(value);
  }

  /**
   * Removes an object from the set.
   *
   * @param object the object to remove
   * @return true if the object is in the set and removed successfully, otherwise false
   */
  @Override
  public boolean remove(Object object) {
    // Locking this object protects against removing the exact object that might be in the
    // process of being added, but does not protect against removing a distinct, but equivalent
    // object.
    if (object == null) {
      return false;
    }
    synchronized (object) {
      if (mObjects.contains(object)) {
        // This isn't technically typesafe. However, given that success is true, it's very unlikely
        // that the object passed to remove is not of type <T>.
        @SuppressWarnings("unchecked")
        T tObj = (T) object;
        removeFromIndices(tObj);
        return mObjects.remove(tObj);
      } else {
        return false;
      }
    }
  }

  /**
   * Helper method that removes an object from the indices.
   *
   * @param object the object to be removed
   */
  private void removeFromIndices(T object) {
    for (Map.Entry<String, FieldIndex<T>> fieldInfo : mIndices.entrySet()) {
      fieldInfo.getValue().remove(object);
    }
  }

  /**
   * Removes the object with the specified unique index field value.
   *
   * @param indexName the field index
   * @param value the field value
   * @return the number of objects removed
   */
  public int removeByField(String indexName, Object value) {
    int removed = 0;
    FieldIndex<T> index = mIndices.get(indexName);

    if (index == null) {
      return 0;
    } else if (index instanceof UniqueFieldIndex) {
      T toRemove = ((UniqueFieldIndex<T>) index).get(value);
      if (remove(toRemove)) {
        removed++;
      }
    } else if (index instanceof NonUniqueFieldIndex) {
      ConcurrentHashSet<T> toRemove = ((NonUniqueFieldIndex<T>) index).get(value);

      if (toRemove == null) {
        return 0;
      }
      for (T o : toRemove) {
        if (remove(o)) {
          removed++;
        }
      }
    }

    return removed;
  }

  /**
   * @return the number of objects in this indexed set (O(1) time)
   */
  @Override
  public int size() {
    return mObjects.size();
  }
}
