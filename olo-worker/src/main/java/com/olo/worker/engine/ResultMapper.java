package com.olo.worker.engine;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.outputcontract.ResultMapping;

import java.util.List;
import java.util.Objects;

/**
 * Single responsibility: map execution variables to the workflow result (output contract).
 * Reads resultMapping and variable map; returns the result string (e.g. first mapped value).
 */
public final class ResultMapper {

    /**
     * Applies the pipeline resultMapping to the variable engine and returns the workflow result string.
     *
     * @param pipeline       pipeline definition (resultMapping)
     * @param variableEngine variable map after execution
     * @return result string, or empty string if no mapping or value
     */
    public static String apply(PipelineDefinition pipeline, VariableEngine variableEngine) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(variableEngine, "variableEngine");
        List<ResultMapping> mapping = pipeline.getResultMapping();
        if (mapping == null || mapping.isEmpty()) {
            return "";
        }
        ResultMapping first = mapping.get(0);
        Object val = variableEngine.get(first.getVariable());
        return val != null ? val.toString() : "";
    }

    private ResultMapper() {
    }
}
