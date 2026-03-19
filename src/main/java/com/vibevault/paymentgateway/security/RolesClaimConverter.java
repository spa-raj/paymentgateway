package com.vibevault.paymentgateway.security;

import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class RolesClaimConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Nullable
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object rolesClaim = jwt.getClaim("roles");

        if (!(rolesClaim instanceof List<?> rolesList)) {
            return Collections.emptyList();
        }

        return rolesList.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toString()))
                .collect(Collectors.toList());
    }
}
