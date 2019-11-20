package org.cyclops.integrateddynamics.core.evaluate.variable;

import net.minecraft.nbt.NBTTagCompound;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.cyclops.cyclopscore.helper.Helpers;
import org.cyclops.cyclopscore.helper.L10NHelpers;
import org.cyclops.integrateddynamics.GeneralConfig;
import org.cyclops.integrateddynamics.api.PartStateException;
import org.cyclops.integrateddynamics.api.evaluate.EvaluationException;
import org.cyclops.integrateddynamics.api.evaluate.operator.IOperator;
import org.cyclops.integrateddynamics.api.evaluate.variable.IValue;
import org.cyclops.integrateddynamics.api.evaluate.variable.IValueType;
import org.cyclops.integrateddynamics.api.evaluate.variable.IVariable;
import org.cyclops.integrateddynamics.api.item.IVariableFacade;
import org.cyclops.integrateddynamics.core.evaluate.operator.CurriedOperator;
import org.cyclops.integrateddynamics.core.helper.L10NValues;
import org.cyclops.integrateddynamics.core.helper.NetworkHelpers;

import com.google.common.annotations.VisibleForTesting;

import javax.annotation.Nullable;

/**
 * A collection of helpers for variables, values and value types.
 * @author rubensworks
 */
public class ValueHelpers {

    /**
     * Create a new value type array from the given variable array element-wise.
     * If a variable would be null, that corresponding value type would be null as well.
     * @param variables The variables.
     * @return The value types array corresponding element-wise to the variables array.
     */
    public static IValueType[] from(IVariable... variables) {
        IValueType[] valueTypes = new IValueType[variables.length];
        for(int i = 0; i < valueTypes.length; i++) {
            IVariable variable = variables[i];
            valueTypes[i] = variable == null ? null : variable.getType();
        }
        return valueTypes;
    }

    /**
     * Create a new value type array from the given variableFacades array element-wise.
     * If a variableFacade would be null, that corresponding value type would be null as well.
     * @param variableFacades The variables facades.
     * @return The value types array corresponding element-wise to the variables array.
     */
    public static IValueType[] from(IVariableFacade... variableFacades) {
        IValueType[] valueTypes = new IValueType[variableFacades.length];
        for(int i = 0; i < valueTypes.length; i++) {
            IVariableFacade variableFacade = variableFacades[i];
            valueTypes[i] = variableFacade == null ? null : variableFacade.getOutputType();
        }
        return valueTypes;
    }

    /**
     * Create a new unlocalized name array from the given variableFacades array element-wise.
     * @param valueTypes The value types.
     * @return The unlocalized names array corresponding element-wise to the value types array.
     */
    public static L10NHelpers.UnlocalizedString[] from(IValueType... valueTypes) {
        L10NHelpers.UnlocalizedString[] names = new L10NHelpers.UnlocalizedString[valueTypes.length];
        for(int i = 0; i < valueTypes.length; i++) {
            IValueType valueType = valueTypes[i];
            names[i] = new L10NHelpers.UnlocalizedString(valueType.getTranslationKey());
        }
        return names;
    }

    /**
     * Check if the two given values are equal.
     * If they are both null, they are also considered equal.
     * @param v1 Value one
     * @param v2 Value two
     * @return If they are equal.
     */
    public static boolean areValuesEqual(@Nullable IValue v1, @Nullable IValue v2) {
        return v1 == null && v2 == null || (!(v1 == null || v2 == null) && v1.equals(v2));
    }

    /**
     * Bidirectional checking of correspondence.
     * @param t1 First type.
     * @param t2 Second type.
     * @return If they correspond to each other in some direction.
     */
    public static boolean correspondsTo(IValueType t1, IValueType t2) {
        return t1.correspondsTo(t2) || t2.correspondsTo(t1);
    }

    /**
     * Evaluate an operator for the given values.
     * @param operator The operator.
     * @param values The values.
     * @return The resulting value.
     * @throws EvaluationException If something went wrong during operator evaluation.
     */
    public static IValue evaluateOperator(IOperator operator, IValue... values) throws EvaluationException {
        IVariable[] variables = new IVariable[values.length];
        for (int i = 0; i < variables.length; i++) {
            IValue value = values[i];
            variables[i] = new Variable<>(value.getType(), value);
        }
        return ValueHelpers.evaluateOperator(operator, variables);
    }

    /**
     * Evaluate an operator for the given variables.
     * @param operator The operator.
     * @param variables The variables.
     * @return The resulting value.
     * @throws EvaluationException If something went wrong during operator evaluation.
     */
    public static IValue evaluateOperator(IOperator operator, IVariable... variables) throws EvaluationException {
        int requiredLength = operator.getRequiredInputLength();
        if (requiredLength == variables.length) {
            return operator.evaluate(variables);
        } else {
            if (variables.length > requiredLength) { // We have MORE variables as input than the operator accepts
                IVariable[] acceptableVariables = ArrayUtils.subarray(variables, 0, requiredLength);
                IVariable[] remainingVariables = ArrayUtils.subarray(variables, requiredLength, variables.length);

                // Pass all required variables to the operator, and forward all remaining ones to the resulting operator
                IValue result = evaluateOperator(operator, acceptableVariables);

                // Error if the result is NOT an operatorø
                if (result.getType() != ValueTypes.OPERATOR) {
                    throw new EvaluationException(String.format(L10NValues.OPERATOR_ERROR_CURRYINGOVERFLOW,
                            operator.getTranslationKey(), requiredLength, variables.length, result.getType()));
                }

                // Pass all remaining variables to the resulting operator
                IOperator nextOperator = ((ValueTypeOperator.ValueOperator) result).getRawValue();
                return evaluateOperator(nextOperator, remainingVariables);

            } else { // Else, the given variables only partially take up the required input
                return ValueTypeOperator.ValueOperator.of(new CurriedOperator(operator, variables));
            }
        }
    }

    /**
     * Serialize the given value to a raw string.
     * @param value The value.
     * @return The NBT tag.
     */
    public static String serializeRaw(IValue value) {
        String raw = value.getType().serialize(value);
        raw = compressSlashes(raw); // TODO: remove this hack
        if (raw.length() >= GeneralConfig.maxValueByteSize) {
            return "TOO LONG";
        }
        return raw;
    }
    
    public static final String compressionMarker = "#H#A#C#K#";
    // pockets of slashes up to this length are ignored
    public static final int slashThreshold = 32;
    
    /**
     * Serializing strings can involve serializing nbt data. The resulting string will add additional escape characters
     * for occurrences of backslashes.
     * If this resulting is serialized again, this will add exponentially more escape characters.
     * Due to this, we need to compress the excessive amount of backslashes. (There is probably a prettier solution)
     */
    public static String compressSlashes(String str) {
    	// only compress if the length is actually going to be a problem
    	if(str == null || str.length() < GeneralConfig.maxValueByteSize / 4) return str;
    	
    	// uncompress so we don't add markers within markers
    	str = uncompressSlashes(str);
    	
    	StringBuilder sb = new StringBuilder();
        int i = 0;
        int count =  0;
        while(i < str.length()) {
        	char c = str.charAt(i);
        	while(c == '\\' && i < str.length() - 1) {
        		count++;
        		i++;
        		c = str.charAt(i);
        	}
        	
        	if(count > slashThreshold) {
        		// leave one slash, so we can count how many times this one got further multiplied and take it into account when uncompressing
        		sb.append(compressionMarker + (count - 1) + "#\\#");
        		count = 0;
        	} else if(count > 0) {
        		sb.append(StringUtils.repeat('\\', count));
        		count = 0;
        	} 
        	sb.append(c);
        	i++;
        }
        return sb.toString();
    }
    
    public static String uncompressSlashes(String str) {
    	if(str == null) 
    		return str;
    	
        String[] strs = str.split(compressionMarker);
        
        if(strs.length == 1) 
        	return str;
        
        StringBuilder sb = new StringBuilder();
        sb.append(strs[0]);
        for(int i = 1; i < strs.length; i++) {
        	// the fragment will look like this: [MARKER]1234#\\\#...
        	// meaning that we have to insert 1234 + 1 slashes multiplied by the number of slashes following between the # (in this case 3)
        	final String fragment = strs[i];
        	final int index = fragment.indexOf("#");
        	final int number = Integer.parseInt(fragment.substring(0, index));
        	
        	int index2 = index;
        	while(fragment.charAt(++index2) != '#');
        	
        	// - 1 because we counted one too much
        	// int followingSlashes = index2 - index - 1;
        	
        	// multiply by the following slashes, because that is how many times slashes got multiplied after the compression was applied
        	sb.append(StringUtils.repeat(fragment.substring(index + 1, index2), number + 1));
        	sb.append(fragment.substring(index2 + 1));
        }
        return sb.toString();
    }
    
    /**
     * Serialize the given value to NBT.
     * @param value The value.
     * @return The NBT tag.
     */
    public static NBTTagCompound serialize(IValue value) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("valueType", value.getType().getTranslationKey());
        tag.setString("value", serializeRaw(value));
        return tag;
    }

    /**
     * Deserialize the given NBT tag to a value.
     * @param tag The NBT tag containing a value.
     * @return The value.
     */
    public static IValue deserialize(NBTTagCompound tag) {
        IValueType valueType = ValueTypes.REGISTRY.getValueType(tag.getString("valueType"));
        if (valueType == null) {
            return null;
        }
        return deserializeRaw(valueType, tag.getString("value"));
    }

    /**
     * Deserialize the given value string to a value.
     * @param valueType The value type to deserialize for.
     * @param valueString The value string.
     * @param <T> The type of value.
     * @return The value.
     */
    public static <T extends IValue> T deserializeRaw(IValueType<T> valueType, String valueString) {
        if ("TOO LONG".equals(valueString)) {
            return valueType.getDefault();
        }
        valueString = uncompressSlashes(valueString); // TODO: remove this hack
        return valueType.deserialize(valueString);
    }

    /**
     * Check if the given result (from the given operator) is a boolean.
     * @param predicate A predicate, used for error logging.
     * @param result A result from the given predicate
     * @throws EvaluationException If the value was not a boolean.
     */
    public static void validatePredicateOutput(IOperator predicate, IValue result) throws EvaluationException {
        if (!(result instanceof ValueTypeBoolean.ValueBoolean)) {
            L10NHelpers.UnlocalizedString error = new L10NHelpers.UnlocalizedString(
                    L10NValues.OPERATOR_ERROR_WRONGPREDICATE,
                    predicate.getLocalizedNameFull(),
                    result.getType(), ValueTypes.BOOLEAN);
            throw new EvaluationException(error.localize());
        }
    }

    /**
     * Get the human readable value of the given value in a safe way.
     * @param variable A nullable variable.
     * @return A pair of a string and color.
     */
    public static Pair<String, Integer> getSafeReadableValue(@Nullable IVariable variable) {
        String readValue = "";
        int readValueColor = 0;
        if (!NetworkHelpers.shouldWork()) {
            readValue = "SAFE-MODE";
        } else if(variable != null) {
            try {
                IValue value = variable.getValue();
                readValue = value.getType().toCompactString(value);
                readValueColor = value.getType().getDisplayColor();
            } catch (EvaluationException | NullPointerException | PartStateException e) {
                readValue = "ERROR";
                readValueColor = Helpers.RGBToInt(255, 0, 0);
            }
        }
        return Pair.of(readValue, readValueColor);
    }

}
