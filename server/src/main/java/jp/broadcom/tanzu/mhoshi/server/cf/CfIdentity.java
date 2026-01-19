package jp.broadcom.tanzu.mhoshi.server.cf;

import java.util.Collection;
import java.util.Objects;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;

record CfIdentity(CfCertificate cfCertificate, CfCertificate serverCertificate) implements UserDetails {

    public static CfIdentity of(String subject, CfCertificate serverCertificate) {
        return new CfIdentity(new CfCertificate(subject), serverCertificate);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (cfCertificate.matchesSpace(serverCertificate)) {
            return AuthorityUtils.createAuthorityList("ROLE_APP");
        }
        return AuthorityUtils.NO_AUTHORITIES;
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return cfCertificate.subject();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (obj == null || obj.getClass() != this.getClass())
            return false;
        var that = (CfIdentity) obj;
        return Objects.equals(this.cfCertificate.subject(), that.cfCertificate.subject());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.cfCertificate.subject());
    }

    @Override
    public String toString() {
        return "CfApp{orgGuid='%s', spaceGuid='%s', appGuid='%s'}".formatted(cfCertificate.appGuid(), cfCertificate.spaceGuid(), cfCertificate.organizationGuid());
    }

}