/*
 * Copyright (c) 1997, 2011, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package java.security;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collections;
import java.io.Serializable;
import java.io.ObjectStreamField;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.IOException;


/**
 * This class represents a heterogeneous collection of Permissions. That is,
 * it contains different types of Permission objects, organized into
 * PermissionCollections. For example, if any
 * <code>java.io.FilePermission</code> objects are added to an instance of
 * this class, they are all stored in a single
 * PermissionCollection. It is the PermissionCollection returned by a call to
 * the <code>newPermissionCollection</code> method in the FilePermission class.
 * Similarly, any <code>java.lang.RuntimePermission</code> objects are
 * stored in the PermissionCollection returned by a call to the
 * <code>newPermissionCollection</code> method in the
 * RuntimePermission class. Thus, this class represents a collection of
 * PermissionCollections.
 *
 * <p>When the <code>add</code> method is called to add a Permission, the
 * Permission is stored in the appropriate PermissionCollection. If no such
 * collection exists yet, the Permission object's class is determined and the
 * <code>newPermissionCollection</code> method is called on that class to create
 * the PermissionCollection and add it to the Permissions object. If
 * <code>newPermissionCollection</code> returns null, then a default
 * PermissionCollection that uses a hashtable will be created and used. Each
 * hashtable entry stores a Permission object as both the key and the value.
 *
 * <p> Enumerations returned via the <code>elements</code> method are
 * not <em>fail-fast</em>.  Modifications to a collection should not be
 * performed while enumerating over that collection.
 *
 * @see Permission
 * @see PermissionCollection
 * @see AllPermission
 *
 *
 * @author Marianne Mueller
 * @author Roland Schemers
 *
 * @serial exclude
 *
 * 这是一个权限集，实现了PermissionCollection类
 * 注意permsMap是缓存，add进入的Permission都会存在这个里面
 */

public final class Permissions extends PermissionCollection
implements Serializable
{
    /**
     * Key is permissions Class, value is PermissionCollection for that class.
     * Not serialized; see serialization section at end of class.
     *
     * 类 -> PermissionCollection
     */
    private transient Map<Class<?>, PermissionCollection> permsMap;

    // optimization. keep track of whether unresolved permissions need to be
    // checked
    // 乐观锁。跟踪是否没有解决的权限集需要被检查
    private transient boolean hasUnresolved = false;

    // optimization. keep track of the AllPermission collection
    // - package private for ProtectionDomain optimization
    // 乐观的。保持跟踪allPermission集合。
    // - 私有的包
    PermissionCollection allPermission;

    /**
     * Creates a new Permissions object containing no PermissionCollections.
     */
    public Permissions() {
        // 初始化11个容量的map
        permsMap = new HashMap<Class<?>, PermissionCollection>(11);
        // 所有权限为空
        allPermission = null;
    }

    /**
     * Adds a permission object to the PermissionCollection for the class the
     * permission belongs to. For example, if <i>permission</i> is a
     * FilePermission, it is added to the FilePermissionCollection stored
     * in this Permissions object.
     *
     * This method creates
     * a new PermissionCollection object (and adds the permission to it)
     * if an appropriate collection does not yet exist. <p>
     *
     * @param permission the Permission object to add.
     *
     * @exception SecurityException if this Permissions object is
     * marked as readonly.
     *
     * @see PermissionCollection#isReadOnly()
     */

    public void add(Permission permission) {
        if (isReadOnly())
            throw new SecurityException(
              "attempt to add a Permission to a readonly Permissions object");

        PermissionCollection pc;

        synchronized (this) {
            // 将permission加入到对应的权限集
            pc = getPermissionCollection(permission, true);
            pc.add(permission);
        }

        // No sync; staleness -> optimizations delayed, which is OK
        if (permission instanceof AllPermission) {
            allPermission = pc;
        }
        if (permission instanceof UnresolvedPermission) {
            hasUnresolved = true;
        }
    }

    /**
     * Checks to see if this object's PermissionCollection for permissions of
     * the specified permission's class implies the permissions
     * expressed in the <i>permission</i> object. Returns true if the
     * combination of permissions in the appropriate PermissionCollection
     * (e.g., a FilePermissionCollection for a FilePermission) together
     * imply the specified permission.
     *
     * <p>For example, suppose there is a FilePermissionCollection in this
     * Permissions object, and it contains one FilePermission that specifies
     * "read" access for  all files in all subdirectories of the "/tmp"
     * directory, and another FilePermission that specifies "write" access
     * for all files in the "/tmp/scratch/foo" directory.
     * Then if the <code>implies</code> method
     * is called with a permission specifying both "read" and "write" access
     * to files in the "/tmp/scratch/foo" directory, <code>true</code> is
     * returned.
     *
     * <p>Additionally, if this PermissionCollection contains the
     * AllPermission, this method will always return true.
     * <p>
     * @param permission the Permission object to check.
     *
     * @return true if "permission" is implied by the permissions in the
     * PermissionCollection it
     * belongs to, false if not.
     */

    public boolean implies(Permission permission) {
        // No sync; staleness -> skip optimization, which is OK
        if (allPermission != null) {
            return true; // AllPermission has already been added
        } else {
            synchronized (this) {
                // 通过permission找到相应的权限集
                PermissionCollection pc = getPermissionCollection(permission,
                    false);
                if (pc != null) {
                    // 查找隐式关系
                    return pc.implies(permission);
                } else {
                    // none found
                    return false;
                }
            }
        }
    }

    /**
     * Returns an enumeration of all the Permission objects in all the
     * PermissionCollections in this Permissions object.
     *
     * @return an enumeration of all the Permissions.
     */

    // 获取所有的元素
    public Enumeration<Permission> elements() {
        // go through each Permissions in the hash table
        // and call their elements() function.

        synchronized (this) {
            return new PermissionsEnumerator(permsMap.values().iterator());
        }
    }

    /**
     * Gets the PermissionCollection in this Permissions object for
     * permissions whose type is the same as that of <i>p</i>.
     * For example, if <i>p</i> is a FilePermission,
     * the FilePermissionCollection
     * stored in this Permissions object will be returned.
     *
     * If createEmpty is true,
     * this method creates a new PermissionCollection object for the specified
     * type of permission objects if one does not yet exist.
     * To do so, it first calls the <code>newPermissionCollection</code> method
     * on <i>p</i>.  Subclasses of class Permission
     * override that method if they need to store their permissions in a
     * particular PermissionCollection object in order to provide the
     * correct semantics when the <code>PermissionCollection.implies</code>
     * method is called.
     * If the call returns a PermissionCollection, that collection is stored
     * in this Permissions object. If the call returns null and createEmpty
     * is true, then
     * this method instantiates and stores a default PermissionCollection
     * that uses a hashtable to store its permission objects.
     *
     * createEmpty is ignored when creating empty PermissionCollection
     * for unresolved permissions because of the overhead of determining the
     * PermissionCollection to use.
     *
     * createEmpty should be set to false when this method is invoked from
     * implies() because it incurs the additional overhead of creating and
     * adding an empty PermissionCollection that will just return false.
     * It should be set to true when invoked from add().
     */
    /**
     * 通过Permission类作为key，因为Permission是不可变的，所以这样做很OK，这需要看看Permission的hashCode实现
     * @param p
     * @param createEmpty
     * @return
     */
    // 通过Permission获取相应的权限集
    private PermissionCollection getPermissionCollection(Permission p,
        boolean createEmpty) {
        Class c = p.getClass();

        PermissionCollection pc = permsMap.get(c);

        if (!hasUnresolved && !createEmpty) {
            return pc;
        } else if (pc == null) {

            // Check for unresolved permissions
            pc = (hasUnresolved ? getUnresolvedPermissions(p) : null);

            // if still null, create a new collection
            if (pc == null && createEmpty) {
                // Permission里面有一个简单的实现方法，但是这个应该视具体的实现类
                pc = p.newPermissionCollection();

                // still no PermissionCollection?
                // We'll give them a PermissionsHash.
                if (pc == null)
                    // 默认返回PermissionsHash
                    pc = new PermissionsHash();
            }

            if (pc != null) {
                permsMap.put(c, pc);
            }
        }
        return pc;
    }

    /**
     * Resolves any unresolved permissions of type p.
     *
     * @param p the type of unresolved permission to resolve
     *
     * @return PermissionCollection containing the unresolved permissions,
     *  or null if there were no unresolved permissions of type p.
     *
     */
    private PermissionCollection getUnresolvedPermissions(Permission p)
    {
        // Called from within synchronized method so permsMap doesn't need lock

        UnresolvedPermissionCollection uc =
        (UnresolvedPermissionCollection) permsMap.get(UnresolvedPermission.class);

        // we have no unresolved permissions if uc is null
        if (uc == null)
            return null;

        List<UnresolvedPermission> unresolvedPerms =
                                        uc.getUnresolvedPermissions(p);

        // we have no unresolved permissions of this type if unresolvedPerms is null
        if (unresolvedPerms == null)
            return null;

        java.security.cert.Certificate certs[] = null;

        Object signers[] = p.getClass().getSigners();

        int n = 0;
        if (signers != null) {
            for (int j=0; j < signers.length; j++) {
                if (signers[j] instanceof java.security.cert.Certificate) {
                    n++;
                }
            }
            certs = new java.security.cert.Certificate[n];
            n = 0;
            for (int j=0; j < signers.length; j++) {
                if (signers[j] instanceof java.security.cert.Certificate) {
                    certs[n++] = (java.security.cert.Certificate)signers[j];
                }
            }
        }

        PermissionCollection pc = null;
        synchronized (unresolvedPerms) {
            int len = unresolvedPerms.size();
            for (int i = 0; i < len; i++) {
                UnresolvedPermission up = unresolvedPerms.get(i);
                Permission perm = up.resolve(p, certs);
                if (perm != null) {
                    if (pc == null) {
                        pc = p.newPermissionCollection();
                        if (pc == null)
                            pc = new PermissionsHash();
                    }
                    pc.add(perm);
                }
            }
        }
        return pc;
    }

    private static final long serialVersionUID = 4858622370623524688L;

    // Need to maintain serialization interoperability with earlier releases,
    // which had the serializable field:
    // private Hashtable perms;

    /**
     * @serialField perms java.util.Hashtable
     *     A table of the Permission classes and PermissionCollections.
     * @serialField allPermission java.security.PermissionCollection
     */
    private static final ObjectStreamField[] serialPersistentFields = {
        new ObjectStreamField("perms", Hashtable.class),
        new ObjectStreamField("allPermission", PermissionCollection.class),
    };

    /**
     * @serialData Default fields.
     */
    /*
     * Writes the contents of the permsMap field out as a Hashtable for
     * serialization compatibility with earlier releases. allPermission
     * unchanged.
     */
    private void writeObject(ObjectOutputStream out) throws IOException {
        // Don't call out.defaultWriteObject()

        // Copy perms into a Hashtable
        Hashtable<Class<?>, PermissionCollection> perms =
            new Hashtable<>(permsMap.size()*2); // no sync; estimate
        synchronized (this) {
            perms.putAll(permsMap);
        }

        // Write out serializable fields
        ObjectOutputStream.PutField pfields = out.putFields();

        pfields.put("allPermission", allPermission); // no sync; staleness OK
        pfields.put("perms", perms);
        out.writeFields();
    }

    /*
     * Reads in a Hashtable of Class/PermissionCollections and saves them in the
     * permsMap field. Reads in allPermission.
     */
    private void readObject(ObjectInputStream in) throws IOException,
    ClassNotFoundException {
        // Don't call defaultReadObject()

        // Read in serialized fields
        ObjectInputStream.GetField gfields = in.readFields();

        // Get allPermission
        allPermission = (PermissionCollection) gfields.get("allPermission", null);

        // Get permissions
        Hashtable<Class<?>, PermissionCollection> perms =
            (Hashtable<Class<?>, PermissionCollection>)gfields.get("perms", null);
        permsMap = new HashMap<Class<?>, PermissionCollection>(perms.size()*2);
        permsMap.putAll(perms);

        // Set hasUnresolved
        UnresolvedPermissionCollection uc =
        (UnresolvedPermissionCollection) permsMap.get(UnresolvedPermission.class);
        hasUnresolved = (uc != null && uc.elements().hasMoreElements());
    }
}

final class PermissionsEnumerator implements Enumeration<Permission> {

    // all the perms
    private Iterator<PermissionCollection> perms;
    // the current set
    private Enumeration<Permission> permset;

    PermissionsEnumerator(Iterator<PermissionCollection> e) {
        perms = e;
        permset = getNextEnumWithMore();
    }

    // No need to synchronize; caller should sync on object as required
    public boolean hasMoreElements() {
        // if we enter with permissionimpl null, we know
        // there are no more left.

        if (permset == null)
            return  false;

        // try to see if there are any left in the current one

        if (permset.hasMoreElements())
            return true;

        // get the next one that has something in it...
        permset = getNextEnumWithMore();

        // if it is null, we are done!
        return (permset != null);
    }

    // No need to synchronize; caller should sync on object as required
    public Permission nextElement() {

        // hasMoreElements will update permset to the next permset
        // with something in it...

        if (hasMoreElements()) {
            return permset.nextElement();
        } else {
            throw new NoSuchElementException("PermissionsEnumerator");
        }

    }

    private Enumeration<Permission> getNextEnumWithMore() {
        while (perms.hasNext()) {
            PermissionCollection pc = perms.next();
            Enumeration<Permission> next =pc.elements();
            if (next.hasMoreElements())
                return next;
        }
        return null;

    }
}

/**
 * A PermissionsHash stores a homogeneous set of permissions in a hashtable.
 *
 * @see Permission
 * @see Permissions
 *
 *
 * @author Roland Schemers
 *
 * @serial include
 */

final class PermissionsHash extends PermissionCollection
implements Serializable
{
    /**
     * Key and value are (same) permissions objects.
     * Not serialized; see serialization section at end of class.
     */
    private transient Map<Permission, Permission> permsMap;

    /**
     * Create an empty PermissionsHash object.
     */

    PermissionsHash() {
        permsMap = new HashMap<Permission, Permission>(11);
    }

    /**
     * Adds a permission to the PermissionsHash.
     *
     * @param permission the Permission object to add.
     */

    public void add(Permission permission) {
        synchronized (this) {
            permsMap.put(permission, permission);
        }
    }

    /**
     * Check and see if this set of permissions implies the permissions
     * expressed in "permission".
     *
     * @param permission the Permission object to compare
     *
     * @return true if "permission" is a proper subset of a permission in
     * the set, false if not.
     */

    public boolean implies(Permission permission) {
        // attempt a fast lookup and implies. If that fails
        // then enumerate through all the permissions.
        // 加锁
        synchronized (this) {
            Permission p = permsMap.get(permission);

            // If permission is found, then p.equals(permission)
            if (p == null) {
                for (Permission p_ : permsMap.values()) {
                    if (p_.implies(permission))
                        return true;
                }
                return false;
            } else {
                return true;
            }
        }
    }

    /**
     * Returns an enumeration of all the Permission objects in the container.
     *
     * @return an enumeration of all the Permissions.
     */

    public Enumeration<Permission> elements() {
        // Convert Iterator of Map values into an Enumeration
        synchronized (this) {
            return Collections.enumeration(permsMap.values());
        }
    }

    private static final long serialVersionUID = -8491988220802933440L;
    // Need to maintain serialization interoperability with earlier releases,
    // which had the serializable field:
    // private Hashtable perms;
    /**
     * @serialField perms java.util.Hashtable
     *     A table of the Permissions (both key and value are same).
     */
    private static final ObjectStreamField[] serialPersistentFields = {
        new ObjectStreamField("perms", Hashtable.class),
    };

    /**
     * @serialData Default fields.
     */
    /*
     * Writes the contents of the permsMap field out as a Hashtable for
     * serialization compatibility with earlier releases.
     */
    private void writeObject(ObjectOutputStream out) throws IOException {
        // Don't call out.defaultWriteObject()

        // Copy perms into a Hashtable
        Hashtable<Permission, Permission> perms =
                new Hashtable<>(permsMap.size()*2);
        synchronized (this) {
            perms.putAll(permsMap);
        }

        // Write out serializable fields
        ObjectOutputStream.PutField pfields = out.putFields();
        pfields.put("perms", perms);
        out.writeFields();
    }

    /*
     * Reads in a Hashtable of Permission/Permission and saves them in the
     * permsMap field.
     */
    private void readObject(ObjectInputStream in) throws IOException,
    ClassNotFoundException {
        // Don't call defaultReadObject()

        // Read in serialized fields
        ObjectInputStream.GetField gfields = in.readFields();

        // Get permissions
        Hashtable<Permission, Permission> perms =
                (Hashtable<Permission, Permission>)gfields.get("perms", null);
        permsMap = new HashMap<Permission, Permission>(perms.size()*2);
        permsMap.putAll(perms);
    }
}
