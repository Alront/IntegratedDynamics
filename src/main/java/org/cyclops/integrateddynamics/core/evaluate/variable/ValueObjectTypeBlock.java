package org.cyclops.integrateddynamics.core.evaluate.variable;

import lombok.ToString;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import org.apache.commons.lang3.tuple.Pair;
import org.cyclops.cyclopscore.helper.BlockHelpers;
import org.cyclops.integrateddynamics.api.evaluate.variable.IValueTypeNamed;

/**
 * Value type with values that are blocks (these are internally stored as blockstates).
 * @author rubensworks
 */
public class ValueObjectTypeBlock extends ValueObjectTypeBase<ValueObjectTypeBlock.ValueBlock> implements IValueTypeNamed<ValueObjectTypeBlock.ValueBlock> {

    public ValueObjectTypeBlock() {
        super("block");
    }

    @Override
    public ValueBlock getDefault() {
        return ValueBlock.of(Blocks.air.getDefaultState());
    }

    @Override
    public String toCompactString(ValueBlock value) {
        return value.getRawValue().getBlock().getLocalizedName();
    }

    @Override
    public String serialize(ValueBlock value) {
        Pair<String, Integer> serializedBlockState = BlockHelpers.serializeBlockState(value.getRawValue());
        return String.format("%s$%s", serializedBlockState.getLeft(), serializedBlockState.getRight());
    }

    @Override
    public ValueBlock deserialize(String value) {
        String[] parts = value.split("\\$");
        try {
            return ValueBlock.of(BlockHelpers.deserializeBlockState(
                    Pair.of(parts[0], Integer.parseInt(parts[1]))
            ));
        } catch (RuntimeException e) {
            e.printStackTrace();
            throw new RuntimeException(String.format("Something went wrong while deserializing '%s'.", value));
        }
    }

    @Override
    public String getName(ValueBlock a) {
        return a.getRawValue().getBlock().getLocalizedName();
    }

    @ToString
    public static class ValueBlock extends ValueBase {

        private final IBlockState blockState;

        private ValueBlock(IBlockState blockState) {
            super(ValueTypes.OBJECT_BLOCK);
            this.blockState = blockState;
        }

        public static ValueBlock of(IBlockState blockState) {
            return new ValueBlock(blockState);
        }

        public IBlockState getRawValue() {
            return blockState;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ValueBlock && ((ValueBlock) o).blockState == this.blockState;
        }
    }

}
