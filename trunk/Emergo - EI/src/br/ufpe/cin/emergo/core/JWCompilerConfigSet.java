package br.ufpe.cin.emergo.core;

import dk.au.cs.java.compiler.ifdef.IfDefVarSet;

public class JWCompilerConfigSet implements ConfigSet {

	private final IfDefVarSet varSet;

	public JWCompilerConfigSet(IfDefVarSet varSet) {
		this.varSet = varSet;
	}

	@Override
	public ConfigSet not() {
		return new JWCompilerConfigSet(varSet.not());
	}

	@Override
	public ConfigSet or(ConfigSet other) {
		if (other instanceof JWCompilerConfigSet) {
			IfDefVarSet otherVarSet = ((JWCompilerConfigSet) other).varSet;
			return new JWCompilerConfigSet(varSet.or(otherVarSet));
		} else {
			throw new IllegalArgumentException("Operation or between types " + JWCompilerConfigSet.class + " and " + other.getClass() + " not supported");
		}
	}

	@Override
	public ConfigSet and(ConfigSet other) {
		if (other instanceof JWCompilerConfigSet) {
			IfDefVarSet otherVarSet = ((JWCompilerConfigSet) other).varSet;
			return new JWCompilerConfigSet(varSet.and(otherVarSet));
		} else {
			throw new IllegalArgumentException("Operation and between types " + JWCompilerConfigSet.class + " and " + other.getClass() + " not supported");
		}
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((varSet == null) ? 0 : varSet.hashCode());
		return result;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		JWCompilerConfigSet other = (JWCompilerConfigSet) obj;
		if (varSet == null) {
			if (other.varSet != null)
				return false;
		} else if (!varSet.equals(other.varSet))
			return false;
		return true;
	}

	
	
}
