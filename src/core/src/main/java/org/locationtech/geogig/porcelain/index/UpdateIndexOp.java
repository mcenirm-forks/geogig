/* Copyright (c) 2017 Boundless and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/edl-v10.html
 *
 * Contributors:
 * Johnathan Garrett (Prominent Edge) - initial implementation
 */
package org.locationtech.geogig.porcelain.index;

import static com.google.common.base.Preconditions.checkState;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;
import org.locationtech.geogig.model.ObjectId;
import org.locationtech.geogig.model.RevFeatureType;
import org.locationtech.geogig.model.RevTree;
import org.locationtech.geogig.plumbing.index.BuildFullHistoryIndexOp;
import org.locationtech.geogig.plumbing.index.BuildIndexOp;
import org.locationtech.geogig.repository.AbstractGeoGigOp;
import org.locationtech.geogig.repository.IndexInfo;
import org.locationtech.geogig.repository.NodeRef;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Updates an {@link IndexInfo} with new metadata.
 */
public class UpdateIndexOp extends AbstractGeoGigOp<Index> {

    private String treeRefSpec;

    private @Nullable String attributeName;

    private @Nullable List<String> extraAttributes;

    private boolean overwrite = false;

    private boolean add = false;

    private boolean indexHistory = false;

    /**
     * @param treeRefSpec the tree refspec of the index to be updated
     * @return {@code this}
     */
    public UpdateIndexOp setTreeRefSpec(String treeRefSpec) {
        this.treeRefSpec = treeRefSpec;
        return this;
    }

    /**
     * @param attributeName the attribute name of the index to be updated
     * @return {@code this}
     */
    public UpdateIndexOp setAttributeName(String attributeName) {
        this.attributeName = attributeName;
        return this;
    }

    /**
     * @param extraAttributes the extra attributes for the updated index
     * @return {@code this}
     * 
     * @see #setAdd(boolean)
     * @see #setOverwrite(boolean)
     */
    public UpdateIndexOp setExtraAttributes(List<String> extraAttributes) {
        this.extraAttributes = extraAttributes;
        return this;
    }

    /**
     * Overwrite old extra attributes with new ones.
     * 
     * @param overwrite if {@code true}, the old extra attributes will be replaced with the new ones
     * @return {@code this}
     */
    public UpdateIndexOp setOverwrite(boolean overwrite) {
        this.overwrite = overwrite;
        return this;
    }

    /**
     * Add new extra attributes to the attributes already being tracked on the index.
     * 
     * @param add if {@code true}, the new extra attributes will be added to the existing ones
     * @return {@code this}
     */
    public UpdateIndexOp setAdd(boolean add) {
        this.add = add;
        return this;
    }

    /**
     * Rebuild the indexes for the full history of the feature tree.
     * 
     * @param indexHistory if {@code true}, the full history of the feature tree will be rebuilt
     * @return {@code this}
     */
    public UpdateIndexOp setIndexHistory(boolean indexHistory) {
        this.indexHistory = indexHistory;
        return this;
    }

    /**
     * Performs the operation.
     * 
     * @return an {@link Index} that represents the updated index
     */
    @Override
    protected Index _call() {
        final RevFeatureType featureType;

        final NodeRef typeTreeRef = IndexUtils.resolveTypeTreeRef(context(), treeRefSpec);
        featureType = objectDatabase().getFeatureType(typeTreeRef.getMetadataId());
        String treeName = typeTreeRef.path();
        IndexInfo oldIndexInfo = IndexUtils.resolveIndexInfo(indexDatabase(), treeName,
                attributeName);
        
        final @Nullable String[] newAttributes = IndexUtils
                .resolveMaterializedAttributeNames(featureType, extraAttributes);

        IndexInfo newIndexInfo = null;
        Map<String, Object> newMetadata = Maps.newHashMap(oldIndexInfo.getMetadata());
        String[] oldAttributes = (String[]) newMetadata
                .get(IndexInfo.FEATURE_ATTRIBUTES_EXTRA_DATA);
        List<String> updatedAttributes;
        if (oldAttributes == null || oldAttributes.length == 0) {
            if (newAttributes == null) {
                updatedAttributes = null;
            } else {
                updatedAttributes = Lists.newArrayList(newAttributes);
            }
        } else {
            checkState(overwrite || add,
                    "Extra attributes already exist on index, specify add or overwrite to update.");
            if (overwrite) {
                if (newAttributes == null) {
                    updatedAttributes = null;
                } else {
                    updatedAttributes = Lists.newArrayList(newAttributes);
                }
            } else {
                updatedAttributes = Lists.newArrayList(oldAttributes);
                if (newAttributes != null) {
                    for (int i = 0; i < newAttributes.length; i++) {
                        if (!updatedAttributes.contains(newAttributes[i])) {
                            updatedAttributes.add(newAttributes[i]);
                        }
                    }
                }
            }
        }
        if (updatedAttributes == null) {
            newMetadata.remove(IndexInfo.FEATURE_ATTRIBUTES_EXTRA_DATA);
        } else {
            newMetadata.put(IndexInfo.FEATURE_ATTRIBUTES_EXTRA_DATA,
                updatedAttributes.toArray(new String[updatedAttributes.size()]));
        }
        newIndexInfo = indexDatabase().updateIndexInfo(treeName, oldIndexInfo.getAttributeName(),
                oldIndexInfo.getIndexType(), newMetadata);

        RevTree canonicalTree = objectDatabase().getTree(typeTreeRef.getObjectId());

        ObjectId indexedTreeId;

        if (indexHistory) {
            command(BuildFullHistoryIndexOp.class)//
                    .setTreeRefSpec(treeRefSpec)//
                    .setAttributeName(oldIndexInfo.getAttributeName())//
                    .setProgressListener(getProgressListener())//
                    .call();
            Optional<ObjectId> headIndexedTreeId = indexDatabase().resolveIndexedTree(newIndexInfo,
                    canonicalTree.getId());
            checkState(headIndexedTreeId.isPresent(),
                    "HEAD indexed tree could not be resolved after building history indexes.");
            indexedTreeId = headIndexedTreeId.get();
        } else {
            indexedTreeId = command(BuildIndexOp.class)//
                    .setIndex(newIndexInfo)//
                    .setOldCanonicalTree(RevTree.EMPTY)//
                    .setNewCanonicalTree(canonicalTree)//
                    .setRevFeatureTypeId(featureType.getId())//
                    .setProgressListener(getProgressListener())//
                    .call().getId();
        }

        return new Index(newIndexInfo, indexedTreeId, indexDatabase());
    }
}
