package org.jboss.resteasy.reactive.server.core.startup;

import static org.jboss.resteasy.reactive.common.util.DeploymentUtils.loadClass;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.ws.rs.RuntimeType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.MessageBodyWriter;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.common.ResteasyReactiveConfig;
import org.jboss.resteasy.reactive.common.model.MethodParameter;
import org.jboss.resteasy.reactive.common.model.ParameterType;
import org.jboss.resteasy.reactive.common.model.ResourceClass;
import org.jboss.resteasy.reactive.common.util.MediaTypeHelper;
import org.jboss.resteasy.reactive.common.util.QuarkusMultivaluedHashMap;
import org.jboss.resteasy.reactive.common.util.ServerMediaType;
import org.jboss.resteasy.reactive.common.util.types.TypeSignatureParser;
import org.jboss.resteasy.reactive.server.core.DeploymentInfo;
import org.jboss.resteasy.reactive.server.core.ServerSerialisers;
import org.jboss.resteasy.reactive.server.core.parameters.AsyncResponseExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.BeanParamExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.BodyParamExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.ContextParamExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.CookieParamExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.FormParamExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.HeaderParamExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.LocatableResourcePathParamExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.MatrixParamExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.NullParamExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.ParameterExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.PathParamExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.QueryParamExtractor;
import org.jboss.resteasy.reactive.server.core.parameters.converters.ParameterConverter;
import org.jboss.resteasy.reactive.server.core.parameters.converters.RuntimeResolvedConverter;
import org.jboss.resteasy.reactive.server.core.serialization.DynamicEntityWriter;
import org.jboss.resteasy.reactive.server.core.serialization.FixedEntityWriter;
import org.jboss.resteasy.reactive.server.core.serialization.FixedEntityWriterArray;
import org.jboss.resteasy.reactive.server.handlers.AbortChainHandler;
import org.jboss.resteasy.reactive.server.handlers.BlockingHandler;
import org.jboss.resteasy.reactive.server.handlers.CompletionStageResponseHandler;
import org.jboss.resteasy.reactive.server.handlers.ExceptionHandler;
import org.jboss.resteasy.reactive.server.handlers.FixedProducesHandler;
import org.jboss.resteasy.reactive.server.handlers.InputHandler;
import org.jboss.resteasy.reactive.server.handlers.InstanceHandler;
import org.jboss.resteasy.reactive.server.handlers.InvocationHandler;
import org.jboss.resteasy.reactive.server.handlers.MultiResponseHandler;
import org.jboss.resteasy.reactive.server.handlers.ParameterHandler;
import org.jboss.resteasy.reactive.server.handlers.PerRequestInstanceHandler;
import org.jboss.resteasy.reactive.server.handlers.ReadBodyHandler;
import org.jboss.resteasy.reactive.server.handlers.RequestDeserializeHandler;
import org.jboss.resteasy.reactive.server.handlers.ResourceLocatorHandler;
import org.jboss.resteasy.reactive.server.handlers.ResponseHandler;
import org.jboss.resteasy.reactive.server.handlers.ResponseWriterHandler;
import org.jboss.resteasy.reactive.server.handlers.SseResponseWriterHandler;
import org.jboss.resteasy.reactive.server.handlers.UniResponseHandler;
import org.jboss.resteasy.reactive.server.handlers.VariableProducesHandler;
import org.jboss.resteasy.reactive.server.mapping.RuntimeResource;
import org.jboss.resteasy.reactive.server.mapping.URITemplate;
import org.jboss.resteasy.reactive.server.model.ParamConverterProviders;
import org.jboss.resteasy.reactive.server.model.ServerMethodParameter;
import org.jboss.resteasy.reactive.server.model.ServerResourceMethod;
import org.jboss.resteasy.reactive.server.spi.EndpointInvoker;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveResourceInfo;
import org.jboss.resteasy.reactive.server.spi.ServerMessageBodyWriter;
import org.jboss.resteasy.reactive.server.spi.ServerRestHandler;
import org.jboss.resteasy.reactive.server.util.ScoreSystem;
import org.jboss.resteasy.reactive.spi.BeanFactory;

public class RuntimeResourceDeployment {

    public static final ServerRestHandler[] EMPTY_REST_HANDLER_ARRAY = new ServerRestHandler[0];

    private static final Logger log = Logger.getLogger(RuntimeResourceDeployment.class);

    private final DeploymentInfo info;
    private final ServerSerialisers serialisers;
    private final ResteasyReactiveConfig quarkusRestConfig;
    private final Supplier<Executor> executorSupplier;
    private final Supplier<ServerRestHandler> blockingInputHandlerSupplier;
    private final RuntimeInterceptorDeployment runtimeInterceptorDeployment;
    private final DynamicEntityWriter dynamicEntityWriter;
    private final ResourceLocatorHandler resourceLocatorHandler;

    public RuntimeResourceDeployment(DeploymentInfo info, Supplier<Executor> executorSupplier,
            Supplier<ServerRestHandler> blockingInputHandlerSupplier,
            RuntimeInterceptorDeployment runtimeInterceptorDeployment, DynamicEntityWriter dynamicEntityWriter,
            ResourceLocatorHandler resourceLocatorHandler) {
        this.info = info;
        this.serialisers = info.getSerialisers();
        this.quarkusRestConfig = info.getConfig();
        this.executorSupplier = executorSupplier;
        this.blockingInputHandlerSupplier = blockingInputHandlerSupplier;
        this.runtimeInterceptorDeployment = runtimeInterceptorDeployment;
        this.dynamicEntityWriter = dynamicEntityWriter;
        this.resourceLocatorHandler = resourceLocatorHandler;
    }

    public RuntimeResource buildResourceMethod(ResourceClass clazz,
            ServerResourceMethod method, boolean locatableResource, URITemplate classPathTemplate) {

        URITemplate methodPathTemplate = new URITemplate(method.getPath(), false);
        List<ServerRestHandler> abortHandlingChain = new ArrayList<>();
        MultivaluedMap<ScoreSystem.Category, ScoreSystem.Diagnostic> score = new QuarkusMultivaluedHashMap<>();

        Map<String, Integer> pathParameterIndexes = buildParamIndexMap(classPathTemplate, methodPathTemplate);
        List<ServerRestHandler> handlers = new ArrayList<>();
        MediaType sseElementType = null;
        if (method.getSseElementType() != null) {
            sseElementType = MediaType.valueOf(method.getSseElementType());
        }
        List<MediaType> consumesMediaTypes;
        if (method.getConsumes() == null) {
            consumesMediaTypes = Collections.emptyList();
        } else {
            consumesMediaTypes = new ArrayList<>(method.getConsumes().length);
            for (String s : method.getConsumes()) {
                consumesMediaTypes.add(MediaType.valueOf(s));
            }
        }

        Class<Object> resourceClass = loadClass(clazz.getClassName());
        Class<?>[] parameterClasses = new Class[method.getParameters().length];
        for (int i = 0; i < method.getParameters().length; ++i) {
            parameterClasses[i] = loadClass(method.getParameters()[i].declaredType);
        }
        ResteasyReactiveResourceInfo lazyMethod = new ResteasyReactiveResourceInfo(method.getName(), resourceClass,
                parameterClasses, method.getMethodAnnotationNames());

        RuntimeInterceptorDeployment.MethodInterceptorContext interceptorDeployment = runtimeInterceptorDeployment
                .forMethod(method, lazyMethod);

        //setup reader and writer interceptors first
        handlers.addAll(interceptorDeployment.setupInterceptorHandler());
        //at this point the handler chain only has interceptors
        //which we also want in the abort handler chain
        abortHandlingChain.addAll(handlers);

        // when a method is blocking, we also want all the request filters to run on the worker thread
        // because they can potentially set thread local variables
        if (method.isBlocking()) {
            handlers.add(new BlockingHandler(executorSupplier));
            score.add(ScoreSystem.Category.Execution, ScoreSystem.Diagnostic.ExecutionBlocking);
        } else {
            score.add(ScoreSystem.Category.Execution, ScoreSystem.Diagnostic.ExecutionNonBlocking);
        }

        //spec doesn't seem to test this, but RESTEeasy does not run request filters again for sub resources (which makes sense)
        if (!locatableResource) {
            handlers.addAll(interceptorDeployment.setupRequestFilterHandler());
        }

        // some parameters need the body to be read
        MethodParameter[] parameters = method.getParameters();
        // body can only be in a parameter
        MethodParameter bodyParameter = null;
        int bodyParameterIndex = -1;
        for (int i = 0; i < parameters.length; i++) {
            MethodParameter param = parameters[i];
            if (param.parameterType == ParameterType.BODY) {
                bodyParameter = param;
                bodyParameterIndex = i;
                break;
            }
        }
        // form params can be everywhere (field, beanparam, param)
        if (method.isFormParamRequired()) {
            // read the body as multipart in one go
            handlers.add(new ReadBodyHandler(bodyParameter != null));
        } else if (bodyParameter != null) {
            if (method.isBlocking() && (blockingInputHandlerSupplier != null)) {
                // when the method is blocking, we will already be on a worker thread
                handlers.add(blockingInputHandlerSupplier.get());
            } else {
                // allow the body to be read by chunks
                handlers.add(new InputHandler(quarkusRestConfig.getInputBufferSize(), executorSupplier));
            }
        }
        // if we need the body, let's deserialise it
        if (bodyParameter != null) {
            Class<Object> typeClass = loadClass(bodyParameter.declaredType);
            Type genericType = typeClass;
            if (!bodyParameter.type.equals(bodyParameter.declaredType)) {
                // we only need to parse the signature and create generic type when the declared type differs from the type
                genericType = TypeSignatureParser.parse(bodyParameter.signature);
            }
            handlers.add(new RequestDeserializeHandler(typeClass, genericType,
                    consumesMediaTypes.isEmpty() ? null : consumesMediaTypes.get(0), serialisers, bodyParameterIndex));
        }

        // given that we may inject form params in the endpoint we need to make sure we read the body before
        // we create/inject our endpoint
        EndpointInvoker invoker = method.getInvoker().get();
        if (!locatableResource) {
            if (clazz.isPerRequestResource()) {
                handlers.add(new PerRequestInstanceHandler(clazz.getFactory(), info.getClientProxyUnwrapper()));
                score.add(ScoreSystem.Category.Resource, ScoreSystem.Diagnostic.ResourcePerRequest);
            } else {
                handlers.add(new InstanceHandler(clazz.getFactory()));
                score.add(ScoreSystem.Category.Resource, ScoreSystem.Diagnostic.ResourceSingleton);
            }
        }

        for (int i = 0; i < parameters.length; i++) {
            ServerMethodParameter param = (ServerMethodParameter) parameters[i];
            boolean single = param.isSingle();
            ParameterExtractor extractor = parameterExtractor(pathParameterIndexes, locatableResource, param.parameterType,
                    param.type, param.name,
                    single, param.encoded);
            ParameterConverter converter = null;
            ParamConverterProviders paramConverterProviders = info.getParamConverterProviders();
            boolean userProviderConvertersExist = !paramConverterProviders.getParamConverterProviders().isEmpty();
            if (param.converter != null) {
                converter = param.converter.get();
                if (userProviderConvertersExist) {
                    Method javaMethod = lazyMethod.getMethod();
                    // Workaround our lack of support for generic params by not doing this init if there are not runtime
                    // param converter providers
                    converter.init(paramConverterProviders, javaMethod.getParameterTypes()[i],
                            javaMethod.getGenericParameterTypes()[i],
                            javaMethod.getParameterAnnotations()[i]);
                    // make sure we give the user provided resolvers the chance to convert
                    converter = new RuntimeResolvedConverter(converter);
                    converter.init(paramConverterProviders, javaMethod.getParameterTypes()[i],
                            javaMethod.getGenericParameterTypes()[i],
                            javaMethod.getParameterAnnotations()[i]);
                }
            }

            handlers.add(new ParameterHandler(i, param.getDefaultValue(), extractor,
                    converter, param.parameterType,
                    param.isObtainedAsCollection()));
        }
        handlers.add(new InvocationHandler(invoker));

        Type returnType = TypeSignatureParser.parse(method.getReturnType());
        Class<?> rawReturnType = getRawType(returnType);
        Type nonAsyncReturnType = getNonAsyncReturnType(returnType);
        Class<?> rawNonAsyncReturnType = getRawType(nonAsyncReturnType);

        if (CompletionStage.class.isAssignableFrom(rawReturnType)) {
            handlers.add(new CompletionStageResponseHandler());
        } else if (Uni.class.isAssignableFrom(rawReturnType)) {
            handlers.add(new UniResponseHandler());
        } else if (Multi.class.isAssignableFrom(rawReturnType)) {
            handlers.add(new MultiResponseHandler());
        }
        ServerMediaType serverMediaType = null;
        if (method.getProduces() != null && method.getProduces().length > 0) {
            // when negotiating a media type, we want to use the proper subtype to locate a ResourceWriter,
            // hence the 'true' for 'useSuffix'
            serverMediaType = new ServerMediaType(ServerMediaType.mediaTypesFromArray(method.getProduces()),
                    StandardCharsets.UTF_8.name(), false, true);
        }
        if (method.getHttpMethod() == null) {
            //this is a resource locator method
            handlers.add(resourceLocatorHandler);
        } else if (!Response.class.isAssignableFrom(rawNonAsyncReturnType)) {
            //try and statically determine the media type and response writer
            //we can't do this for all cases, but we can do it for the most common ones
            //in practice this should work for the majority of endpoints
            if (method.getProduces() != null && method.getProduces().length > 0) {
                //the method can only produce a single content type, which is the most common case
                if (method.getProduces().length == 1) {
                    MediaType mediaType = MediaType.valueOf(method.getProduces()[0]);
                    //its a wildcard type, makes it hard to determine statically
                    if (mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
                        handlers.add(new VariableProducesHandler(serverMediaType, serialisers));
                        score.add(ScoreSystem.Category.Writer, ScoreSystem.Diagnostic.WriterRunTime);
                    } else if (rawNonAsyncReturnType != Void.class
                            && rawNonAsyncReturnType != void.class) {
                        List<MessageBodyWriter<?>> buildTimeWriters = serialisers.findBuildTimeWriters(rawNonAsyncReturnType,
                                RuntimeType.SERVER, Collections.singletonList(
                                        MediaTypeHelper.withSuffixAsSubtype(MediaType.valueOf(method.getProduces()[0]))));
                        if (buildTimeWriters == null) {
                            //if this is null this means that the type cannot be resolved at build time
                            //this happens when the method returns a generic type (e.g. Object), so there
                            //are more specific mappers that could be invoked depending on the actual return value
                            handlers.add(new FixedProducesHandler(mediaType, dynamicEntityWriter));
                            score.add(ScoreSystem.Category.Writer, ScoreSystem.Diagnostic.WriterRunTime);
                        } else if (buildTimeWriters.isEmpty()) {
                            //we could not find any writers that can write a response to this endpoint
                            log.warn("Cannot find any combination of response writers for the method " + clazz.getClassName()
                                    + "#" + method.getName() + "(" + Arrays.toString(method.getParameters()) + ")");
                            handlers.add(new VariableProducesHandler(serverMediaType, serialisers));
                            score.add(ScoreSystem.Category.Writer, ScoreSystem.Diagnostic.WriterRunTime);
                        } else if (buildTimeWriters.size() == 1) {
                            //only a single handler that can handle the response
                            //this is a very common case
                            MessageBodyWriter<?> writer = buildTimeWriters.get(0);
                            handlers.add(new FixedProducesHandler(mediaType, new FixedEntityWriter(
                                    writer, serialisers)));
                            if (writer instanceof ServerMessageBodyWriter)
                                score.add(ScoreSystem.Category.Writer,
                                        ScoreSystem.Diagnostic.WriterBuildTimeDirect(writer));
                            else
                                score.add(ScoreSystem.Category.Writer,
                                        ScoreSystem.Diagnostic.WriterBuildTime(writer));
                        } else {
                            //multiple writers, we try them in the proper order which had already been created
                            handlers.add(new FixedProducesHandler(mediaType,
                                    new FixedEntityWriterArray(buildTimeWriters.toArray(new MessageBodyWriter[0]),
                                            serialisers)));
                            score.add(ScoreSystem.Category.Writer,
                                    ScoreSystem.Diagnostic.WriterBuildTimeMultiple(buildTimeWriters));
                        }
                    } else {
                        score.add(ScoreSystem.Category.Writer, ScoreSystem.Diagnostic.WriterNotRequired);
                    }
                } else {
                    //there are multiple possibilities
                    //we could optimise this more in future
                    handlers.add(new VariableProducesHandler(serverMediaType, serialisers));
                    score.add(ScoreSystem.Category.Writer, ScoreSystem.Diagnostic.WriterRunTime);
                }
            } else {
                score.add(ScoreSystem.Category.Writer, ScoreSystem.Diagnostic.WriterRunTime);
            }
        } else {
            score.add(ScoreSystem.Category.Writer, ScoreSystem.Diagnostic.WriterRunTime);
        }

        //the response filter handlers, they need to be added to both the abort and
        //normal chains. At the moment this only has one handler added to it but
        //in future there will be one per filter
        List<ServerRestHandler> responseFilterHandlers = new ArrayList<>();
        if (method.isSse()) {
            handlers.add(new SseResponseWriterHandler());
        } else {
            handlers.add(new ResponseHandler());

            responseFilterHandlers.addAll(interceptorDeployment.setupResponseFilterHandler());
            handlers.addAll(responseFilterHandlers);
            handlers.add(new ResponseWriterHandler(dynamicEntityWriter));
        }
        abortHandlingChain.add(new ExceptionHandler());
        abortHandlingChain.add(new ResponseHandler());
        abortHandlingChain.addAll(responseFilterHandlers);

        abortHandlingChain.add(new ResponseWriterHandler(dynamicEntityWriter));
        handlers.add(0, new AbortChainHandler(abortHandlingChain.toArray(EMPTY_REST_HANDLER_ARRAY)));

        RuntimeResource runtimeResource = new RuntimeResource(method.getHttpMethod(), methodPathTemplate,
                classPathTemplate,
                method.getProduces() == null ? null : serverMediaType,
                consumesMediaTypes, invoker,
                clazz.getFactory(), handlers.toArray(EMPTY_REST_HANDLER_ARRAY), method.getName(), parameterClasses,
                nonAsyncReturnType, method.isBlocking(), resourceClass,
                lazyMethod,
                pathParameterIndexes, score, sseElementType, clazz.resourceExceptionMapper());
        return runtimeResource;
    }

    public ParameterExtractor parameterExtractor(Map<String, Integer> pathParameterIndexes, boolean locatableResource,
            ParameterType type, String javaType,
            String name,
            boolean single, boolean encoded) {
        ParameterExtractor extractor;
        switch (type) {
            case HEADER:
                return new HeaderParamExtractor(name, single);
            case COOKIE:
                return new CookieParamExtractor(name);
            case FORM:
                return new FormParamExtractor(name, single, encoded);
            case PATH:
                Integer index = pathParameterIndexes.get(name);
                if (index == null) {
                    if (locatableResource) {
                        extractor = new LocatableResourcePathParamExtractor(name);
                    } else {
                        extractor = new NullParamExtractor();
                    }
                } else {
                    extractor = new PathParamExtractor(index, encoded);
                }
                return extractor;
            case CONTEXT:
                return new ContextParamExtractor(javaType);
            case ASYNC_RESPONSE:
                return new AsyncResponseExtractor();
            case QUERY:
                extractor = new QueryParamExtractor(name, single, encoded);
                return extractor;
            case BODY:
                return new BodyParamExtractor();
            case MATRIX:
                extractor = new MatrixParamExtractor(name, single, encoded);
                return extractor;
            case BEAN:
                return new BeanParamExtractor((BeanFactory<Object>) info.getFactoryCreator().apply(loadClass(javaType)));
            default:
                return new QueryParamExtractor(name, single, encoded);
        }
    }

    public Map<String, Integer> buildParamIndexMap(URITemplate classPathTemplate, URITemplate methodPathTemplate) {
        Map<String, Integer> pathParameterIndexes = new HashMap<>();
        int pathCount = 0;
        if (classPathTemplate != null) {
            for (URITemplate.TemplateComponent i : classPathTemplate.components) {
                if (i.name != null) {
                    pathParameterIndexes.put(i.name, pathCount++);
                } else if (i.names != null) {
                    for (String nm : i.names) {
                        pathParameterIndexes.put(nm, pathCount++);
                    }
                }
            }
        }
        for (URITemplate.TemplateComponent i : methodPathTemplate.components) {
            if (i.name != null) {
                pathParameterIndexes.put(i.name, pathCount++);
            } else if (i.names != null) {
                for (String nm : i.names) {
                    pathParameterIndexes.put(nm, pathCount++);
                }
            }
        }
        return pathParameterIndexes;
    }

    private Class<?> getRawType(Type type) {
        if (type instanceof Class)
            return (Class<?>) type;
        if (type instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType) type;
            return (Class<?>) ptype.getRawType();
        }
        throw new UnsupportedOperationException("Endpoint return type not supported yet: " + type);
    }

    private Type getNonAsyncReturnType(Type returnType) {
        if (returnType instanceof Class)
            return returnType;
        if (returnType instanceof ParameterizedType) {
            // NOTE: same code in EndpointIndexer.getNonAsyncReturnType
            ParameterizedType type = (ParameterizedType) returnType;
            if (type.getRawType() == CompletionStage.class) {
                return type.getActualTypeArguments()[0];
            }
            if (type.getRawType() == Uni.class) {
                return type.getActualTypeArguments()[0];
            }
            if (type.getRawType() == Multi.class) {
                return type.getActualTypeArguments()[0];
            }
            return returnType;
        }
        throw new UnsupportedOperationException("Endpoint return type not supported yet: " + returnType);
    }

}
