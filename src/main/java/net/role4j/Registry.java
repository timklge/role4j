package net.role4j;

import es.uniovi.reflection.invokedynamic.ProxyFactory;
import es.uniovi.reflection.invokedynamic.interfaces.Callable;
import es.uniovi.reflection.invokedynamic.util.Bootstrap;
import es.uniovi.reflection.invokedynamic.util.Cache;
import es.uniovi.reflection.invokedynamic.util.MethodSignature;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by nguonly on 4/24/16.
 */
public class Registry {
    public HashMap<Integer, HashMap<Integer, CallableMethod>> hashCallables = new HashMap<>(); //Proxy Object + HashMap<Method.hashCode, CallabeMethod>

    private ArrayDeque<ProxyRole> proxyRoles = new ArrayDeque<>();

    private ArrayDeque<Relation> relations = new ArrayDeque<>();

    public HashMap<Integer, CallableMethod> compartmentCallable = new HashMap<>();

    private HashMap<Long, ICompartment> activeCompartments = new HashMap<>(); //CurrentThread, Compartment

    public HashMap<Integer, CallableMethod> coreCallable = new HashMap<>();

    private static Registry registry;

    public synchronized static Registry getRegistry() {
        if (registry == null) {
            registry = new Registry();
        }
        return registry;
    }

    public ArrayDeque<Relation> getRelations() {
        return relations;
    }

    public void setRelations(ArrayDeque<Relation> relations) {
        this.relations = relations;
    }

    public <T extends IRole> IRole bindCore(IPlayer object, Object role) throws Throwable {
        ICompartment compartment = getActiveCompartment();
        if (compartment == null) throw new RuntimeException("No compartment was found");

        Class<T> roleType = (Class<T>) role.getClass();

        IPlayer core = (IPlayer) object.getClass().getField("_real").get(object);

        Object realComparment = compartment.getClass().getField("_real").get(compartment);

        //Check if this is the first binding and then binding the core method
        Optional<Relation> optCoreRelation = relations.stream()
                .filter(p -> p.proxyCompartment.equals(compartment) && p.proxyRole.equals(object) && p.level == 0 && p.sequence == 0)
                .findFirst();
        if (!optCoreRelation.isPresent()) {
            //first binding. 1) register callable for core and 2) register core as proxy role
            bindCoreToCore(compartment, realComparment, core, object);
        }

        T proxyRole = proxyRole(compartment, core, object, role, roleType);

        Relation forLvlSeq = LookupTableService.getLevelAndSequence(compartment, object);

        Relation r = new Relation();
        r.proxyCompartment = compartment;
        r.compartment = realComparment;
        r.compartmentType = realComparment.getClass();
        r.proxyObject = object;
        r.object = core;
        r.objectType = core.getClass();
        r.proxyPlayer = object;
        r.player = core; //previously work
        r.playerType = ReflectionHelper.getAllSuperClassesAndInterfaces(core.getClass()); //no need to get proxy class
        r.role = role;
        r.proxyRole = proxyRole;
        r.roleType = roleType;
        r.level = forLvlSeq.level;
        r.sequence = forLvlSeq.sequence;

        relations.add(r);

        reRegisterCallable(compartment, object);

        return proxyRole;
    }

    public <T extends IRole> IRole bindCore(IPlayer object, Class<T> roleType, Object... args) throws Throwable {
        T role = ReflectionHelper.newInstance(roleType, args);
        return bindCore(object, role);
    }

    private void bindCoreToCore(Object compartment, Object realCompartment, Object core, Object proxyCore) {
        ProxyRole pr = new ProxyRole();
        pr.compartment = compartment;
        pr.proxyObject = proxyCore;
        pr.object = core;
        pr.role = core;
        pr.proxyRole = proxyCore; // proxyRoles
        pr.proxyRoleType = ReflectionHelper.getAllSuperClassesAndInterfaces(core.getClass());
        proxyRoles.add(pr);

        //TODO: check proxy role might have duplicated Core. e.g person -> student -> teaching_assistant and person -> father
        //Register core as a role with level=0 and sequence=0
        Relation coreRelation = new Relation();
        coreRelation.proxyCompartment = compartment;
        coreRelation.compartment = realCompartment;
        coreRelation.compartmentType = realCompartment.getClass();
        coreRelation.proxyObject = proxyCore;
        coreRelation.object = core;
        coreRelation.objectType = core.getClass();
        coreRelation.proxyPlayer = proxyCore;
        coreRelation.player = core;
        coreRelation.playerType = ReflectionHelper.getAllSuperClassesAndInterfaces(core.getClass()); //no need to get proxy class
        coreRelation.role = core;
        coreRelation.proxyRole = proxyCore;
        coreRelation.roleType = core.getClass();
        coreRelation.level = 0;
        coreRelation.sequence = 0;
        relations.add(coreRelation);
    }

    public <T extends IRole> IRole bindRole(IPlayer pxRoleAsPlayer, Class<T> roleType, Object... args) throws Throwable {
        ICompartment compartment = getActiveCompartment();
        if (compartment == null) throw new RuntimeException("No compartment was found");

        T role = ReflectionHelper.newInstance(roleType, args);

        //find core object
        Optional<Relation> optRelRoleAsPlayer = relations.stream()
                .filter(p -> p.proxyCompartment.equals(compartment) && p.proxyRole.equals(pxRoleAsPlayer))
                .findFirst();

        IPlayer core = (IPlayer) optRelRoleAsPlayer.get().object;
        Object proxyCore = optRelRoleAsPlayer.get().proxyObject;

        Object realComparment = compartment.getClass().getField("_real").get(compartment);

        T proxyRole = proxyRole(compartment, core, proxyCore, role, roleType, args);

        Relation forLvlSeq = LookupTableService.getLevelAndSequence(compartment, pxRoleAsPlayer);

        Relation r = new Relation();
        r.proxyCompartment = compartment;
        r.compartment = realComparment;
        r.compartmentType = realComparment.getClass();
        r.proxyObject = proxyCore;
        r.object = core;
        r.objectType = optRelRoleAsPlayer.get().objectType;

        r.proxyPlayer = pxRoleAsPlayer;
        r.player = optRelRoleAsPlayer.get().role;
        r.playerType = ReflectionHelper.getAllSuperClassesAndInterfaces(pxRoleAsPlayer.getClass().getSuperclass()); //no need proxy class

        r.role = role;
        r.proxyRole = proxyRole;
        r.roleType = roleType;
        r.level = forLvlSeq.level;
        r.sequence = forLvlSeq.sequence;

        relations.add(r);

        reRegisterCallable(compartment, proxyCore);

        return proxyRole;
    }

    private Object proxyHandler(Object core, Method method, Object... args) throws Throwable {
        ICompartment compartment = getActiveCompartment();

        String m; //method
        if (compartment == null || !isBound(core)) { //invoke from core
            m = core.hashCode() + getMethodForKey(method);
            CallableMethod cm = coreCallable.get(m.hashCode());
            return cm.callable.invoke(cm.invokingObject, args);
        } else { //invoke from the playing relation
            m = compartment.hashCode() + core.hashCode() + getMethodForKey(method);
            int hashCode = (compartment.hashCode() + ":" + core.hashCode()).hashCode();
            HashMap<Integer, CallableMethod> hash = hashCallables.get(hashCode);
            CallableMethod cm = hash.get(m.hashCode());
            return cm.callable.invoke(cm.invokingObject, args);
        }
    }

    private <T> T proxyRole(ICompartment compartment, Object core, Object proxyObject, Object role, Class<T> roleType, Object... args) throws Throwable {

        Class<? extends T> roleClass = new ByteBuddy(ClassFileVersion.forCurrentJavaVersion())
                .subclass(roleType)
                .defineField("_real", IPlayer.class, Visibility.PUBLIC)
                .method(ElementMatchers.isDeclaredBy(roleType))
                .intercept(InvocationHandlerAdapter.of((proxy, method, m_args) -> proxyHandler(core, method, m_args)))
                .make()
                .load(roleType.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();

        T proxyRole = ReflectionHelper.newInstance(roleClass, args);
        roleClass.getField("_real").set(proxyRole, role);

        ProxyRole pr = new ProxyRole();
        pr.compartment = compartment;
        pr.proxyObject = proxyObject;
        pr.object = core;
        pr.role = role;
        pr.proxyRole = proxyRole; // proxyRoles
        pr.proxyRoleType = ReflectionHelper.getAllSuperClassesAndInterfaces(roleType);
        proxyRoles.add(pr);

        return proxyRole;
    }

    public void unbind(Object player, boolean isCore, Class<?> roleType) throws Throwable {
        ICompartment compartment = getActiveCompartment();
        if (compartment == null) throw new RuntimeException("No active compartment was found");

        Optional<Relation> optRelation;
        Object proxyObject = null;
        if (isCore) {
            optRelation = relations.stream()
                    .filter(p -> p.proxyCompartment.equals(compartment) &&
                            p.proxyObject.equals(player) && p.roleType.equals(roleType))
                    .findFirst();
        } else {
            optRelation = relations.stream()
                    .filter(p -> p.proxyCompartment.equals(compartment) &&
                            p.proxyPlayer.equals(player) && p.roleType.equals(roleType))
                    .findFirst();

            proxyObject = relations.stream()
                    .filter(p -> p.proxyCompartment.equals(compartment) &&
                            p.proxyRole.equals(player))
                    .findFirst().get().proxyObject;
        }

        Relation relation = optRelation.get();

        List<Relation> rs = traverseRelation(relation);
        //first remove the current relation
        relations.removeIf(p -> p.equals(relation));
        proxyRoles.removeIf(p -> p.proxyRole == relation.proxyRole);
        rs.forEach(k -> {
            relations.removeIf(p -> p.equals(k));
            proxyRoles.removeIf(p -> p.proxyRole == k.proxyRole);
        });

        //Method composition
        reRegisterCallable(compartment, isCore ? player : proxyObject);
    }

    public void reRegisterCallable(ICompartment compartment, Object object) {
        Optional<Relation> optCore = relations.stream()
                .filter(p -> p.proxyCompartment.equals(compartment) &&
                        p.proxyObject.equals(object) && p.proxyRole.equals(object) && p.level == 0 && p.sequence == 0)
                .findFirst();

        Relation coreRelation = optCore.get();

        int hashCode = (compartment.hashCode() + ":" + coreRelation.object.hashCode()).hashCode();

        //clear the a particular instance in a particular compartment
        hashCallables.put(hashCode, new HashMap<>());

        List<Relation> rs = traverseRelation(coreRelation);
        //register core
        registerCallable(compartment, coreRelation.object, coreRelation.object, coreRelation.objectType);

        for (Relation r : rs) {
            registerCallable(compartment, r.object, r.role, r.roleType);

        }
    }

    /**
     * Transfer a role instance from a player of a compartment to another player in either the same or different compartment.
     *
     * @param transferringRole
     * @param fromPlayer
     * @param toPlayer
     * @param toCompartment
     */
    public void transfer(Object transferringRole, Object fromPlayer, Object toPlayer, ICompartment toCompartment) throws Throwable {
        ICompartment fromCompartment = getActiveCompartment();
        if (fromCompartment == null) throw new RuntimeException("Now active compartment was found");

        Optional<Relation> optCore = relations.stream()
                .filter(x -> x.proxyCompartment.equals(fromCompartment) &&
                        x.proxyRole.equals(transferringRole))
                .findFirst();

        //get transferring traversing
        Optional<Relation> optRelation = relations.stream()
                .filter(p -> p.proxyCompartment.equals(fromCompartment) && p.proxyRole.equals(transferringRole))
                .findFirst();
        List<Relation> transferringRoleList = traverseRelation(optRelation.get());

        Relation relation = optRelation.get();
        //first remove the current relation
        relations.removeIf(p -> p.equals(relation));
        proxyRoles.removeIf(p -> p.proxyRole == relation.proxyRole);
        transferringRoleList.forEach(k -> {
            relations.removeIf(p -> p.equals(k));
            proxyRoles.removeIf(p -> p.proxyRole == k.proxyRole);
        });

        Relation core = optCore.get();
        reRegisterCallable(fromCompartment, core.proxyObject);

        //Assign transferringRoleList to new player and compartment
        Relation toPlayerRelation;

        toPlayerRelation = new Relation();
        toPlayerRelation.proxyObject = toPlayer;
        toPlayerRelation.object = toPlayer.getClass().getField("_real").get(toPlayer);
        toPlayerRelation.objectType = toPlayerRelation.object.getClass();
        toPlayerRelation.player = toPlayerRelation.object;
        toPlayerRelation.playerType = ReflectionHelper.getAllSuperClassesAndInterfaces(toPlayerRelation.object.getClass());
        toPlayerRelation.proxyPlayer = toPlayer;
        toPlayerRelation.role = transferringRole.getClass().getField("_real").get(transferringRole);
        toPlayerRelation.proxyRole = transferringRole;
        toPlayerRelation.roleType = transferringRole.getClass().getSuperclass();

        transferringRoleList.add(0, toPlayerRelation);

        //find the relation of toPlayer
        Optional<Relation> optToPlayer = relations.stream()
                .filter(p -> p.proxyCompartment.equals(toCompartment) && p.proxyPlayer.equals(toPlayer))
                .max(Comparator.comparing(p -> p.sequence));


        Object realCompartment = toCompartment.getClass().getField("_real").get(toCompartment);
        Object toProxyCore;
        if (optToPlayer.isPresent()) {
            toPlayerRelation = optToPlayer.get();
            toProxyCore = toPlayerRelation.proxyObject;
        } else { //bind to a core object with no existing relation

            toProxyCore = toPlayer;

            bindCoreToCore(toCompartment, realCompartment, toPlayerRelation.object, toPlayer);
        }
        for (Relation tRole : transferringRoleList) {
            Object role = tRole.role;
            Class roleType = tRole.roleType;

            Object proxyRole = proxyRole(toCompartment, toPlayerRelation.object, toPlayerRelation.proxyObject, role, roleType);

            Relation forLvlSeq = LookupTableService.getLevelAndSequenceFromRole(toCompartment, (IPlayer) tRole.player);

            Relation r = new Relation();
            r.compartment = realCompartment;
            r.proxyCompartment = toCompartment;
            r.compartmentType = toCompartment.getClass();
            r.proxyCompartment = toCompartment;
            r.proxyObject = toPlayerRelation.proxyObject;
            r.object = toPlayerRelation.object;
            r.objectType = toPlayerRelation.objectType;

            r.proxyPlayer = tRole.proxyPlayer;
            r.player = tRole.player;
            r.playerType = tRole.playerType; //no need proxy class

            r.role = tRole.role;
            r.proxyRole = proxyRole;
            r.roleType = tRole.roleType;
            r.level = forLvlSeq.level;
            r.sequence = forLvlSeq.sequence;

            relations.add(r);
        }

        //Method composition
        reRegisterCallable(toCompartment, toProxyCore);

    }

    private void registerCallable(ICompartment compartment, Object object, Object role, Class clazz) {
        int compId = compartment.hashCode();
        int objectId = object.hashCode();

        Method[] methods = clazz.getDeclaredMethods();
        int hashCode = (compId + ":" + objectId).hashCode();
        HashMap<Integer, CallableMethod> hash = hashCallables.get(hashCode); //get callable method on an object

        for (Method method : methods) {
            Callable<?> c = getCallable(clazz, method);
            String m = compId + objectId + getMethodForKey(method);

            CallableMethod cm = new CallableMethod(m, role, c);

            hash.put(m.hashCode(), cm); //put each method
        }
    }

    private void registerCompartmentCallable(Object compartment, Class<?> clazz) {
        Method[] methods = clazz.getDeclaredMethods();

        for (Method method : methods) {
            Callable<?> c = getCallable(clazz, method);
            String m = compartment.hashCode() + getMethodForKey(method);
            CallableMethod cm = new CallableMethod(m, compartment, c);
            compartmentCallable.put(m.hashCode(), cm);
        }
    }

    private void registerCoreCallable(Object core, Class<?> clazz) {
        Method[] methods = clazz.getDeclaredMethods();

        for (Method method : methods) {
            Callable<?> c = getCallable(clazz, method);
            String m = core.hashCode() + getMethodForKey(method);
            CallableMethod cm = new CallableMethod(m, core, c);
            coreCallable.put(m.hashCode(), cm);
        }
    }

    private <T> Callable<T> getCallable(Class<T> clazz, Method method) {
        try {
            Bootstrap bootstrap = new Bootstrap(Cache.Save, "net.role4j.MyBootstrap", "callDispatch", clazz, method.getName());
            MethodSignature sig = new MethodSignature(method.getReturnType(), clazz, method.getParameterTypes());

            //Class rType = method.getReturnType();
            Callable<T> callable = ProxyFactory.generateInvokeDynamicCallable(bootstrap, sig);

            return callable;
        } catch (Throwable t) {
            t.printStackTrace();
        }

        return null;
    }

    public List<Relation> traverseRelation(Relation relation) {
        ICompartment compartment = (ICompartment) relation.proxyCompartment;

        List<Relation> list = new ArrayList<>();

        List<Relation> relations = relation.nextBindingLevelRoles(compartment);
        for (Relation rel : relations) {
            list.add(rel);
            List<Relation> r = rel.nextBindingLevelRoles(compartment);
            if (r.size() > 0) list.addAll(traverseRelation(rel));
        }

        return list;
    }

    public <T> T newCore(Class<T> clazz, Object... args) throws Throwable {

        final T core = ReflectionHelper.newInstance(clazz, args);

        Class<? extends T> proxyType = new ByteBuddy(ClassFileVersion.forCurrentJavaVersion())
                .subclass(clazz)
                .defineField("_real", IPlayer.class, Visibility.PUBLIC)
                .method(ElementMatchers.isDeclaredBy(clazz))
                .intercept(InvocationHandlerAdapter.of((proxy, method, m_args) -> proxyHandler(core, method, m_args)))
                .make()
                .load(clazz.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();


        T proxyCore = ReflectionHelper.newInstance(proxyType, args);
        //set temporary core object
        proxyType.getField("_real").set(proxyCore, core);

        registerCoreCallable(core, core.getClass());

        return proxyCore;
    }

    /**
     * Check if the core has been bound in the relations. This helps for the dispatcher
     *
     * @param core
     * @return
     */
    private boolean isBound(Object core) {
        Optional<Relation> optCoreRelation = relations.stream()
                .filter(c -> c.object.equals(core))
                .findFirst();

        return optCoreRelation.isPresent();
    }

    public <T> T newCompartment(Class<T> compartmentType, Object... args) throws Throwable {
        T realCompartment = ReflectionHelper.newInstance(compartmentType, args);

        Class<? extends T> proxyType = new ByteBuddy(ClassFileVersion.forCurrentJavaVersion())
                .subclass(compartmentType)
                .defineField("_real", IPlayer.class, Visibility.PUBLIC)
                .method(ElementMatchers.isDeclaredBy(compartmentType))
                .intercept(InvocationHandlerAdapter.of((proxy, method, proxyArgs) -> {
                    String m = realCompartment.hashCode() + getMethodForKey(method);
                    CallableMethod cm = compartmentCallable.get(m.hashCode());
                    return cm.callable.invoke(cm.invokingObject, proxyArgs);
                }))
                .make()
                .load(compartmentType.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();

        T proxyCompartment = ReflectionHelper.newInstance(proxyType, args);

        proxyType.getField("_real").set(proxyCompartment, realCompartment);

        //register indy method call
        registerCompartmentCallable(realCompartment, compartmentType);

        return proxyCompartment;
    }

    public void activateCompartment(ICompartment compartment) {
        long threadId = Thread.currentThread().getId();

        //will override the existing compartment
        activeCompartments.put(threadId, compartment);
    }

    public void deactivateCompartment(ICompartment compartment) {
        long threadId = Thread.currentThread().getId();
        activeCompartments.remove(threadId);
    }

    private ICompartment getActiveCompartment() {
        long threadId = Thread.currentThread().getId();

        ICompartment compartment = activeCompartments.get(threadId);

        return compartment;
    }

    /**
     * Get method for constructing as the key for method caching in composition
     * @param method
     * @return
     */
    private String getMethodForKey(Method method) {
        String name = method.getName();
        Class[] paramTypes = method.getParameterTypes();

        String paramTypesStr = "";
        for (Class param : paramTypes) {
            paramTypesStr += param.getName() + ", ";
        }
        if (paramTypes.length > 0) {
            paramTypesStr = paramTypesStr.substring(0, paramTypesStr.length() - 2);
        }

        return name + "(" + paramTypesStr + ")";
    }

    public <T> Object getCore(Object role) {
        ICompartment compartment = getActiveCompartment();
        if (compartment == null) throw new RuntimeException("No compartment was found");

        Optional<Relation> r = relations.stream()
                .filter(x -> x.proxyCompartment.equals(compartment) &&
                        x.role.equals(role))
                .findFirst();

        return r.isPresent() ? r.get().proxyObject : null;
    }

    public <T> List<IPlayer> getCores(Class<T> roleType) {
        List<IPlayer> list = relations.stream()
                .filter(x -> x.roleType.equals(roleType))
                .map((k) -> IPlayer.class.cast(k.proxyObject))
                .collect(Collectors.toList());

        return list;
    }

    public <T> Object getPlayer(Object role) {
        ICompartment compartment = getActiveCompartment();
        if (compartment == null) throw new RuntimeException("No compartment was found");

        Optional<Relation> r = relations.stream()
                .filter(x -> x.role.equals(role))
                .findFirst();

        return r.isPresent() ? r.get().player : null;
    }

    /**
     * Get player from common type such as Abstract class or Interface.
     * The purpose of having this function is to support partial methods
     * decorated over cascading calls in the role-playing-role relation.
     *
     * @param role       current role being asked for player
     * @param playerType the type of player. It should be interface or abstract class
     * @param <T>        player type
     * @return the return of player instance (NOT the PROXY of player)
     */
    public <T> T getPlayer(Object role, Class<T> playerType) {
        ICompartment compartment = getActiveCompartment();
        if (compartment == null) throw new RuntimeException("No compartment was found");

        Optional<Relation> r = relations.stream()
                .filter(p -> p.proxyCompartment.equals(compartment) && p.role.equals(role)
                        && p.playerType.contains(playerType))
                .findFirst();

        //TODO: Check the resulting of infinite call due to proxying

        if (r.isPresent()) {
            Relation player = r.get();
            if (player.proxyPlayer.equals(player.proxyObject)) {//this is core
                return playerType.cast(player.object); //return the real core not the proxy
            } else {
                return playerType.cast(r.get().player);
            }
        }

        return null;
    }

    //player either core or role in case of role plays role
    public <T> T getRoleInstance(IPlayer player, Class<T> roleType) {
        ICompartment compartment = getActiveCompartment();
        if (compartment == null) throw new RuntimeException("No compartment was found");

        Optional<Relation> optRoleRelation = relations.stream()
                .filter(p -> p.proxyCompartment.equals(compartment) && p.proxyObject.equals(player)
                        && p.roleType.equals(roleType))
                .findFirst();

        return optRoleRelation.isPresent() ? roleType.cast(optRoleRelation.get().role) : null;
    }

    /**
     * Get compartment in the current relations
     *
     * @param player          is either core or role (role plays role)
     * @param compartmentType
     * @param <T>
     * @return
     */
    public <T> T getCompartment(IPlayer player, Class<T> compartmentType) {
        ICompartment compartment = getActiveCompartment();
        if (compartment == null) throw new RuntimeException("No compartment was found");

        boolean isRole = player instanceof IRole;

        //TODO: Check compartmentType because it can be evaluated even we pass compartmentType as an Interface.
        Optional<Relation> optCompartmentRelation = relations.stream()
                .filter(p -> p.proxyCompartment.equals(compartment) &&
                        isRole ? p.role.equals(player) : p.proxyObject.equals(player) &&
                        p.compartmentType.equals(compartmentType))
                .findFirst();

        if (optCompartmentRelation.isPresent()) {
            return compartmentType.cast(optCompartmentRelation.get().compartment);
        }
        return null;
    }

    public <T> T interfaceOf(Object proxyPlayer, boolean isCore, Class<T> clazz) throws Throwable {
        ICompartment compartment = getActiveCompartment();

        Object core = isCore ? proxyPlayer : getCore(proxyPlayer);

        Optional<ProxyRole> r = proxyRoles.stream()
                .filter(x -> x.compartment.equals(compartment) && x.proxyObject.equals(core)
                        && x.proxyRoleType.contains(clazz))
                .findFirst();

        if (r.isPresent()) {
            ProxyRole pr = r.get();
            //System.out.println(pr.role);
            return clazz.cast(pr.proxyRole);
        } else {
            return null;
        }
    }

    public <T> T coreOf(Object role, Class<T> clazz, Class<T> c) throws Throwable {
        Object core = getCore(role);
        Optional<ProxyRole> r = proxyRoles.stream()
                //.filter(x -> x.proxyObject.equals(core) && x.proxyRoleType.equals(clazz))
                .filter(x -> x.proxyObject.equals(core))
                .findFirst();

        return c.cast(clazz.newInstance());
    }
}
